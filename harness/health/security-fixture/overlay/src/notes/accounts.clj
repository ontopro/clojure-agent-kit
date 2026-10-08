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
  (db/exec-one! conn {:select [:id :username :password_hash :admin]
                      :from [:users]
                      :where [:= :username username]}))

(defn find-by-id
  [conn id]
  (db/exec-one! conn {:select [:id :username :admin]
                      :from [:users]
                      :where [:= :id id]}))

(defn register!
  "Create an account; the first one is the administrator. Nil when the username is taken."
  [conn username password]
  (when-not (find-by-username conn username)
    (let [first? (zero? (:n (db/exec-one! conn {:select [[[:count :*] :n]] :from [:users]})))]
      (db/exec-one! conn {:insert-into :users
                          :values [{:username username
                                    :password_hash (hash-password password)
                                    :admin (if first? 1 0)}]})
      (find-by-username conn username))))

(defn authenticate
  "The account for `username` when `password` is its password, else nil."
  [conn username password]
  (let [user (find-by-username conn username)]
    (when (and user (password-matches? password (:password-hash user)))
      user)))

(defn all-users
  [conn]
  (db/exec! conn {:select [:id :username :admin] :from [:users] :order-by [:id]}))

(defn make-admin!
  [conn user-id]
  (db/exec-one! conn {:update :users :set {:admin 1} :where [:= :id user-id]}))
