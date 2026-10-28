(ns toolchain-install
  "The pack's edge: fetching an archive and taking its sha256. `toolchain-sum` lands here first
  (plan 7.0, item 8.1b) - each platform's archive of a tool fetched once into a temporary folder,
  its sha256 taken and set beside the publisher's, the lines to paste into the matrix printed;
  the paste is the person's, as a pin move is. The install itself joins in 8.1c.

  `fetch!` and `sha256-of` are the seam a test redefines; `sum-rows` is pure over what they give."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [toolchain-download-matrix :as dm]))

(defn fetch!
  "`url` to the file `to`, following redirects; throws on a status that is not 200."
  [url to]
  (let [r (http/get url {:as :stream :throw false})]
    (when-not (= 200 (:status r))
      (throw (ex-info (str url " answered " (:status r)) {:url url :status (:status r)})))
    (with-open [in (:body r)] (io/copy in (io/file to)))
    to))

(defn fetch-text!
  "`url`'s body as text, or nil when it does not answer 200 - a publisher that publishes no
  checksum file."
  [url]
  (let [r (http/get url {:throw false})]
    (when (= 200 (:status r)) (:body r))))

(defn sha256-of
  "The file's sha256, lower-case hex."
  [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (io/file f))]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n) (.update md buf 0 n) (recur)))))
    (format "%064x" (BigInteger. 1 (.digest md)))))

(defn sum-row
  "One archive's row: the sha256 taken, the publisher's (nil when it publishes none), the matrix's,
  and what they say together - `:matches`, `:differs` from the publisher, `:unpublished`."
  [{:keys [platform file sha256]} taken published]
  {:platform platform :file file :sha256 taken :matrix sha256 :published published
   :says (cond (nil? published) :unpublished
               (= taken published) :matches
               :else :differs)})

(defn sum-lines
  "The rows as lines to read and paste: per platform, the matrix's `{:sha256 \"…\"}` and the word."
  [tool version rows]
  (into [(str (name tool) " " version)]
        (for [{:keys [platform file sha256 matrix says]} rows]
          (str "  " (if platform (str platform " ") "") "{:sha256 \"" sha256 "\"}  ; " file " - "
               (case says
                 :matches "the publisher's checksum matches"
                 :differs "DIFFERS from the publisher's checksum - not to be pasted"
                 :unpublished "the publisher publishes no checksum: computed here")
               (cond (str/blank? matrix) ", the matrix has none yet"
                     (= matrix sha256) ", the matrix has it"
                     :else ", the matrix has ANOTHER")))))

(defn sum-main
  "The `toolchain-sum <tool> [<version>]` task. Exit 1 when an archive differs from its publisher's
  checksum."
  [pack-dir [tool version]]
  (let [matrix (dm/read-matrix (str pack-dir "/download-matrix.edn"))
        k (keyword tool)
        _ (when-not (get matrix k)
            (binding [*out* *err*]
              (println "toolchain-sum <tool> [<version>] - a tool of download-matrix.edn:"
                       (str/join ", " (map name (keys matrix)))))
            (System/exit 1))
        matrix (cond-> matrix version (assoc-in [k :version] version))
        dir (fs/create-temp-dir {:prefix "toolchain-sum-"})
        ;; the folder deleted before any exit: System/exit runs no finally
        rows (try
               (vec (for [a (dm/assets matrix k)]
                      (let [f (str dir "/" (:file a))]
                        (binding [*out* *err*] (println "fetching" (:url a)))
                        (fetch! (:url a) f)
                        (let [taken (sha256-of f)]
                          (fs/delete f)
                          (sum-row a taken (some-> (:checksum-url a) fetch-text!
                                                   (dm/published-sha256 (:file a))))))))
               (finally (fs/delete-tree dir)))]
    (run! println (sum-lines k (get-in matrix [k :version]) rows))
    (when (some #(= :differs (:says %)) rows) (System/exit 1))))
