(ns notes.reference-test
  "The KIT's reference reproductions for the security fixture: one test per planted fault, each
  stating the property the clean feature keeps. Each passes on the clean feature and fails on
  the faulted one with its fault in place. Run in the fixture's build only, never handed to an
  auditor: the whole handler, called in-process with a cookie jar and the real CSRF token,
  against an SQLite file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [integrant.core :as ig]
            [notes.accounts :as accounts]
            [notes.db :as db]
            [notes.server :as server])
  (:import (java.io ByteArrayInputStream)
           (java.net URLEncoder)
           (java.util.concurrent CountDownLatch)))

(def ^:dynamic *app* nil)
(def ^:dynamic *db* nil)
(def ^:dynamic *dir* nil)

(defn with-app
  [f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "notes-ref" (make-array java.nio.file.attribute.FileAttribute 0)))
        ds (ig/init-key ::db/db {:jdbc-url (str "jdbc:sqlite:" (io/file dir "notes.sqlite"))})]
    (try
      (binding [*db* ds
                *dir* dir
                *app* (server/ring-handler {:options {:session-secret-key "test-secret-key"
                                                      :cookie-attrs-secure? false
                                                      :exports-dir (str (io/file dir "exports"))}
                                            :db ds})]
        (f))
      (finally (ig/halt-key! ::db/db ds)))))

(use-fixtures :each with-app)

;; ---------------------------------------------------------------------------
;; a browser: a cookie jar and the page's CSRF token
;; ---------------------------------------------------------------------------

(defn- form-body [params]
  (str/join "&" (for [[k v] params] (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))))

(defn- browser [] (atom {:cookies {} :token nil}))

(defn- set-cookies [response]
  (let [v (get-in response [:headers "Set-Cookie"])]
    (for [c (if (string? v) [v] v)
          :let [[k val] (str/split (first (str/split c #";")) #"=" 2)]]
      [k val])))

(defn- send!
  [b method uri & [params]]
  (let [cookie (str/join "; " (for [[k v] (:cookies @b)] (str k "=" v)))
        query? (= :get method)
        req (cond-> {:request-method method :uri uri :scheme :http :server-name "localhost" :server-port 80
                     :headers (cond-> {"cookie" cookie}
                                (not query?) (assoc "content-type" "application/x-www-form-urlencoded"))}
              (and query? (seq params)) (assoc :query-string (form-body params))
              (not query?) (assoc :body (ByteArrayInputStream. (.getBytes (form-body params) "UTF-8"))))
        response (*app* req)
        body (let [bd (:body response)] (if (string? bd) bd (some-> bd slurp)))]
    (swap! b update :cookies into (set-cookies response))
    (when-let [t (some->> body (re-find #"name=\"__anti-forgery-token\"[^>]*value=\"([^\"]+)\"") second)]
      (swap! b assoc :token t))
    (assoc response :body body)))

(defn- post! [b uri params]
  (send! b :post uri (assoc params :__anti-forgery-token (:token @b))))

(defn- user! [username]
  (let [b (browser)]
    (send! b :get "/register")
    (post! b "/register" {:username username :password "correct horse"})
    (send! b :get "/login")
    (post! b "/login" {:username username :password "correct horse"})
    (send! b :get "/notes")
    b))

(defn- user-id [username] (:id (accounts/find-by-username *db* username)))

(defn- notes-of [username]
  (db/exec! *db* {:select [:id :title :body] :from [:notes] :where [:= :user_id (user-id username)]}))

;; ---------------------------------------------------------------------------
;; one property per planted fault
;; ---------------------------------------------------------------------------

(deftest the-quota-holds-when-notes-are-added-at-once
  (let [b (user! "alice")
        start (CountDownLatch. 1)
        posts (doall (for [i (range 12)]
                       (future (.await start) (post! b "/notes" {:title (str "n" i) :body "x"}))))]
    (.countDown start)
    (run! deref posts)
    (is (<= (count (notes-of "alice")) 3))))

(deftest only-an-administrator-makes-an-administrator
  (user! "admin")
  (let [bob (user! "bob")]
    (post! bob "/admin/users" {:user-id (user-id "bob")})
    (is (= 0 (:admin (accounts/find-by-id *db* (user-id "bob")))))))

(deftest a-search-reads-only-the-searchers-notes
  (let [alice (user! "alice")
        bob (user! "bob")]
    (post! bob "/notes" {:title "bob-private" :body "x"})
    (post! alice "/notes" {:title "mine" :body "x"})
    (let [page (send! alice :get "/notes" {:q "x' OR 1=1 OR title LIKE '"})]
      (is (not (str/includes? (str (:body page)) "bob-private"))))))

(deftest a-note-is-deleted-only-by-a-post-with-its-token
  (let [alice (user! "alice")]
    (post! alice "/notes" {:title "keep" :body "x"})
    (let [id (:id (first (notes-of "alice")))]
      (send! alice :get (str "/notes/" id "/delete"))
      (is (= 1 (count (notes-of "alice")))))))

(deftest an-export-serves-its-owners-file-and-nothing-else
  (let [alice (user! "alice")]
    (spit (io/file *dir* "outside.txt") "outside the exports folder")
    (send! alice :get "/export")
    (let [r (send! alice :get "/export/download" {:name "../outside.txt"})]
      (is (not (str/includes? (str (:body r)) "outside the exports folder"))))))

(deftest a-note-is-read-only-by-its-owner
  (let [alice (user! "alice")
        bob (user! "bob")]
    (post! bob "/notes" {:title "bob-private" :body "bob-secret-body"})
    (let [id (:id (first (notes-of "bob")))
          r (send! alice :get (str "/notes/" id))]
      (is (not (str/includes? (str (:body r)) "bob-secret-body"))))))

(deftest the-feature-works-for-its-owner
  ;; the control: a reproduction that fails here is broken, not a finding
  (let [alice (user! "alice")]
    (post! alice "/notes" {:title "first" :body "hello"})
    (is (= ["first"] (map :title (notes-of "alice"))))
    (is (str/includes? (:body (send! alice :get "/notes" {:q "fir"})) "first"))
    (let [id (:id (first (notes-of "alice")))]
      (is (str/includes? (:body (send! alice :get (str "/notes/" id))) "hello"))
      (send! alice :get "/export")
      (is (str/includes? (:body (send! alice :get "/export/download" {:name "alice.txt"})) "hello"))
      (send! alice :get (str "/notes/" id))
      (post! alice (str "/notes/" id "/delete") {})
      (is (empty? (notes-of "alice"))))))

;; ---------------------------------------------------------------------------
;; four properties no fault is planted against: they hold on BOTH branches, and were added when a
;; security reviewer found the careful feature breaking them (a first-administrator race; a username
;; that became a file name; a login whose username was a map, which HoneySQL read as SQL; a session
;; that outlived its logout). A finding of any of them is now an invention to score, not a hit.
;; ---------------------------------------------------------------------------

(defn- usernames [] (set (map :username (db/exec! *db* {:select [:username] :from [:users]}))))

(deftest only-one-of-the-first-registrations-at-once-is-an-administrator
  (let [start (CountDownLatch. 1)
        registrations (doall (for [i (range 8)]
                               (future (let [b (browser)]
                                         (send! b :get "/register")
                                         (.await start)
                                         (post! b "/register" {:username (str "user" i) :password "correct horse"})))))]
    (.countDown start)
    (run! deref registrations)
    (is (= 8 (count (usernames))) "every registration was accepted")
    (is (= 1 (:n (db/exec-one! *db* {:select [[[:count :*] :n]] :from [:users] :where [:= :admin 1]}))))))

(deftest a-username-is-letters-digits-and-hyphens-and-never-a-path
  (let [b (browser)]
    (send! b :get "/register")
    (doseq [bad ["../outside" "a/b" "x y" "ab" "..\\x" "a.b" (apply str (repeat 33 "a"))]]
      (post! b "/register" {:username bad :password "correct horse"}))
    (is (empty? (usernames)) "none of these became an account")
    (post! b "/register" {:username "good_name-1" :password "correct horse"})
    (is (= #{"good_name-1"} (usernames)) "an ordinary name still does")))

(deftest a-username-sent-as-a-map-is-not-sql
  ;; `username[raw]=...` reaches the handler as {:raw ...} through the parameter middleware
  (user! "victim")
  (user! "intruder")
  (let [b (browser)
        payload (str "'') UNION SELECT id, username, "
                     "(SELECT password_hash FROM users WHERE username='intruder'), admin, session_generation "
                     "FROM users WHERE username='victim' --")]
    (send! b :get "/login")
    (is (= 401 (:status (post! b "/login" {(keyword "username[raw]") payload :password "correct horse"}))))
    (is (= 302 (:status (send! b :get "/notes"))) "no login came of it")))

(deftest a-session-ends-at-logout
  (let [alice (user! "alice")
        copy (atom @alice)]
    (is (= 200 (:status (send! copy :get "/notes"))) "a copy of the cookie is a login before")
    (post! alice "/logout" {})
    (is (= 302 (:status (send! copy :get "/notes"))) "and is not one after")))
