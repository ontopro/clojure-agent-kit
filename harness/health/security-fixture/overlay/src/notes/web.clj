(ns notes.web
  "The notes pages: register, log in, a user's notes, an export of them, and the
  administrator's list of accounts."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [notes.accounts :as accounts]
            [notes.notebook :as notebook]
            [notes.views :as views]
            [reitit-extras.core :as reitit-extras]
            [ring.util.response :as response]))

(defn- conn [request] (get-in request [:context :db]))

(defn- exports-dir [request] (get-in request [:context :options :exports-dir] "exports"))

(defn- page
  [& content]
  (reitit-extras/render-html
   (views/base (into [:main {:class ["container" "mx-auto" "p-6" "max-w-2xl"]}] content))))

(defn- form
  [action & fields]
  (into [:form {:method "post"
                :action action
                :class ["my-4"]} (reitit-extras/csrf-token-html)] fields))

;; ---------------------------------------------------------------------------
;; who is asking
;; ---------------------------------------------------------------------------

(defn current-user
  [request]
  (some->> (get-in request [:session :user-id]) (accounts/find-by-id (conn request))))

(defn wrap-require-login
  "Only a logged-in user past here; anyone else to the login page."
  [handler]
  (fn [request]
    (if-let [user (current-user request)]
      (handler (assoc request :user user))
      (response/redirect "/login"))))

(defn wrap-require-admin
  "Only an administrator past here. Expects `wrap-require-login` before it."
  [handler]
  (fn [request]
    (if (= 1 (get-in request [:user :admin]))
      (handler request)
      (-> (page [:h1 "Not allowed"]) (response/status 403)))))

;; ---------------------------------------------------------------------------
;; accounts
;; ---------------------------------------------------------------------------

(defn login-page
  [_]
  (page [:h1 "Log in"]
        (form "/login"
              [:input {:name "username"
                       :placeholder "username"}]
              [:input {:name "password"
                       :type "password"
                       :placeholder "password"}]
              [:button {:type "submit"} "Log in"])
        [:a {:href "/register"} "Register"]))

(defn login!
  [{{:keys [username password]} :params
    :as request}]
  (if-let [user (accounts/authenticate (conn request) username password)]
    (-> (response/redirect "/notes" :see-other)
        (assoc :session {:user-id (:id user)}))
    (-> (page [:h1 "Log in"] [:p "Wrong username or password."]) (response/status 401))))

(defn logout!
  [_]
  (-> (response/redirect "/login" :see-other) (assoc :session nil)))

(defn register-page
  [_]
  (page [:h1 "Register"]
        (form "/register"
              [:input {:name "username"
                       :placeholder "username"}]
              [:input {:name "password"
                       :type "password"
                       :placeholder "password"}]
              [:button {:type "submit"} "Register"])))

(defn register!
  [{{:keys [username password]} :params
    :as request}]
  (cond
    (or (str/blank? username) (< (count (str password)) 8))
    (-> (page [:h1 "Register"] [:p "A username, and a password of eight characters or more."]) (response/status 400))
    (accounts/register! (conn request) username password)
    (response/redirect "/login" :see-other)
    :else
    (-> (page [:h1 "Register"] [:p "That username is taken."]) (response/status 409))))

;; ---------------------------------------------------------------------------
;; notes
;; ---------------------------------------------------------------------------

(defn notes-page
  [{:keys [user]
    {:keys [q]} :params
    :as request}]
  (let [notes (notebook/notes-for (conn request) (:id user) q)]
    (page [:h1 (:username user) "'s notes"]
          [:form {:method "get"
                  :action "/notes"}
           [:input {:name "q"
                    :value q
                    :placeholder "search titles"}]
           [:button {:type "submit"} "Search"]]
          [:ul (for [n notes]
                 [:li [:a {:href (str "/notes/" (:id n))} (:title n)]])]
          (form "/notes"
                [:input {:name "title"
                         :placeholder "title"}]
                [:textarea {:name "body"}]
                [:button {:type "submit"} "Add"])
          [:p [:a {:href "/export"} "Export my notes"]]
          (form "/logout" [:button {:type "submit"} "Log out"]))))

(defn add-note!
  [{:keys [user]
    {:keys [title body]} :params
    :as request}]
  (if (str/blank? title)
    (-> (page [:h1 "A note needs a title"]) (response/status 400))
    (if (notebook/add-note! (conn request) (:id user) title (str body))
      (response/redirect "/notes" :see-other)
      (-> (page [:h1 "You have " notebook/quota " notes, the most you can keep"]) (response/status 409)))))

(defn- note-id [request] (parse-long (str (get-in request [:path-params :id]))))

(defn note-page
  [{:keys [user]
    :as request}]
  (if-let [n (some->> (note-id request) (notebook/note-for (conn request) (:id user)))]
    (page [:h1 (:title n)]
          [:p (:body n)]
          (form (str "/notes/" (:id n) "/delete") [:button {:type "submit"} "Delete"])
          [:a {:href "/notes"} "Back"])
    (-> (page [:h1 "No such note"]) (response/status 404))))

(defn delete-note!
  [{:keys [user]
    :as request}]
  (when-let [id (note-id request)]
    (notebook/delete-note! (conn request) (:id user) id))
  (response/redirect "/notes" :see-other))

;; ---------------------------------------------------------------------------
;; export
;; ---------------------------------------------------------------------------

(defn- export-name [user] (str (:username user) ".txt"))

(defn export-page
  "Write the user's notes to their export file and link to it."
  [{:keys [user]
    :as request}]
  (let [dir (exports-dir request)
        name (export-name user)]
    (.mkdirs (io/file dir))
    (spit (io/file dir name)
          (str/join "\n\n" (for [n (notebook/notes-for (conn request) (:id user) nil)]
                             (str (:title n) "\n" (:body n)))))
    (page [:h1 "Export"]
          [:a {:href (str "/export/download?name=" name)} name])))

(defn download-export
  "The user's own export file, and no other."
  [{:keys [user]
    {:keys [name]} :params
    :as request}]
  (let [f (io/file (exports-dir request) (export-name user))]
    (if (and (= name (export-name user)) (.isFile f))
      (-> (response/response (slurp f))
          (response/content-type "text/plain")
          (response/header "Content-Disposition" (str "attachment; filename=\"" (export-name user) "\"")))
      (-> (page [:h1 "No such export"]) (response/status 404)))))

;; ---------------------------------------------------------------------------
;; administration
;; ---------------------------------------------------------------------------

(defn users-page
  [request]
  (page [:h1 "Accounts"]
        [:ul (for [u (accounts/all-users (conn request))]
               [:li (:username u) (when (= 1 (:admin u)) " (administrator)")
                (when-not (= 1 (:admin u))
                  (form "/admin/users"
                        [:input {:type "hidden"
                                 :name "user-id"
                                 :value (:id u)}]
                        [:button {:type "submit"} "Make administrator"]))])]))

(defn make-admin!
  [{{:keys [user-id]} :params
    :as request}]
  (when-let [id (parse-long (str user-id))]
    (accounts/make-admin! (conn request) id))
  (response/redirect "/admin/users" :see-other))
