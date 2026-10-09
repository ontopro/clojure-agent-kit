(ns notes.accounts
  "Accounts: a username and a password, hashed with PBKDF2. The first account
  registered is the administrator."
  (:require [clojure.string :as str]
            [notes.db :as db])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)
           (javax.crypto SecretKeyFactory)
           (javax.crypto.spec PBEKeySpec)))

(def ^:private iterations 120000)

(defn- pbkdf2
  [^String password ^bytes salt]
  (let [spec (PBEKeySpec. (.toCharArray password) salt iterations 256)]
    (.getEncoded (.generateSecret (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256") spec))))

(defn hash-password
  "A salted hash of `password`, as one string to store."
  [password]
  (let [salt (byte-array 16)
        enc (Base64/getEncoder)]
    (.nextBytes (SecureRandom.) salt)
    (str "pbkdf2$" iterations "$" (.encodeToString enc salt) "$" (.encodeToString enc (pbkdf2 password salt)))))

(defn password-matches?
  [password stored]
  (let [[_ _ salt hash] (str/split (str stored) #"\$")
        dec (Base64/getDecoder)]
    (boolean (and salt hash
                  (MessageDigest/isEqual (pbkdf2 password (.decode dec ^String salt))
                                         (.decode dec ^String hash))))))

(defn find-by-username
  [conn username]
  (db/exec-one! conn {:select [:id :username :password_hash :admin :session_generation]
                      :from [:users]
                      :where [:= :username username]}))

(defn find-by-id
  [conn id]
  (db/exec-one! conn {:select [:id :username :admin :session_generation]
                      :from [:users]
                      :where [:= :id id]}))

(def username-pattern #"[A-Za-z0-9_-]{3,32}")

(defn valid-username?
  "Three to thirty-two letters, digits, hyphens and underscores. A username is shown in pages and
  names the user's export file, so it is never a path."
  [username]
  (boolean (and (string? username) (re-matches username-pattern username))))

(defn register!
  "Create an account; the first one is the administrator. Nil when the username is not valid or is
  taken. Whether the account is the first is decided by the statement that inserts it, so
  registrations at once cannot all find the table empty."
  [conn username password]
  (when (and (valid-username? username) (not (find-by-username conn username)))
    (try
      (db/exec-one! conn {:insert-into [[:users [:username :password_hash :admin]]
                                        {:select [[[:lift username]]
                                                  [[:lift (hash-password password)]]
                                                  [[:case [:= {:select [[[:count :*]]] :from [:users]} 0] 1 :else 0]]]}]})
      (find-by-username conn username)
      (catch java.sql.SQLException _ nil))))

(defn authenticate
  "The account for `username` when `password` is its password, else nil. Both must be strings: the
  parameter middleware makes a map of `username[x]=...`, and a map where a value belongs is SQL to
  HoneySQL."
  [conn username password]
  (when (and (string? username) (string? password))
    (let [user (find-by-username conn username)]
      (when (and user (password-matches? password (:password-hash user)))
        user))))

(defn end-sessions!
  "End every session of the account: a session carries the generation it was opened in, and one
  from an earlier generation is no longer a login. The session is a signed cookie the server keeps
  no copy of, so this is the only way one stops working."
  [conn user-id]
  (db/exec-one! conn {:update :users
                      :set {:session_generation [:+ :session_generation 1]}
                      :where [:= :id user-id]}))

(defn all-users
  [conn]
  (db/exec! conn {:select [:id :username :admin] :from [:users] :order-by [:id]}))

(defn make-admin!
  [conn user-id]
  (db/exec-one! conn {:update :users :set {:admin 1} :where [:= :id user-id]}))
