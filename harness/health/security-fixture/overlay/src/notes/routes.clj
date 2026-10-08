(ns notes.routes
  (:require [notes.handlers :as handlers]
            [notes.web :as web]
            [ring.util.response :as response]))

(def routes
  [["/" {:name ::home
         :get {:handler handlers/home-handler}
         :responses {200 {:body string?}}}]
   ["/health" {:name ::health-check
               :get {:handler (fn [_] (response/response "OK"))}}]
   ["/login" {:get {:handler web/login-page}
              :post {:handler web/login!}}]
   ["/logout" {:post {:handler web/logout!}}]
   ["/register" {:get {:handler web/register-page}
                 :post {:handler web/register!}}]
   ["/notes" {:middleware [web/wrap-require-login]}
    ["" {:get {:handler web/notes-page}
         :post {:handler web/add-note!}}]
    ["/:id" {:get {:handler web/note-page}}]
    ["/:id/delete" {:post {:handler web/delete-note!}}]]
   ["/export" {:middleware [web/wrap-require-login]}
    ["" {:get {:handler web/export-page}}]
    ["/download" {:get {:handler web/download-export}}]]
   ["/admin/users" {:middleware [web/wrap-require-login web/wrap-require-admin]
                    :get {:handler web/users-page}
                    :post {:handler web/make-admin!}}]])
