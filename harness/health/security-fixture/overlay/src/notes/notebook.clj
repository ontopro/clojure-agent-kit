(ns notes.notebook
  "A user's notes: each user sees and changes only their own, and keeps at most
  `quota` of them."
  (:require [notes.db :as db]))

(def quota 3)

(defn notes-for
  "The user's notes, newest first; those whose title contains `q` when it is given."
  [conn user-id q]
  (db/exec! conn {:select [:id :title :body :created_at]
                  :from [:notes]
                  :where (cond-> [:and [:= :user_id user-id]]
                           (seq q) (conj [:like :title (str "%" q "%")]))
                  :order-by [[:id :desc]]}))

(defn note-for
  "One of the user's notes, or nil."
  [conn user-id note-id]
  (db/exec-one! conn {:select [:id :title :body :created_at]
                      :from [:notes]
                      :where [:and [:= :id note-id] [:= :user_id user-id]]}))

(defn add-note!
  "Add a note within the quota: true when it was added, false when the user is at the quota.
  One statement, so two requests at once cannot both pass the count."
  [conn user-id title body]
  (let [r (db/exec-one! conn {:insert-into [[:notes [:user_id :title :body]]
                                            {:select [[[:lift user-id]] [[:lift title]] [[:lift body]]]
                                             :where [:< {:select [[[:count :*]]]
                                                         :from [:notes]
                                                         :where [:= :user_id user-id]}
                                                     quota]}]})]
    (pos? (or (:next.jdbc/update-count r) 0))))

(defn delete-note!
  [conn user-id note-id]
  (db/exec-one! conn {:delete-from :notes
                      :where [:and [:= :id note-id] [:= :user_id user-id]]}))
