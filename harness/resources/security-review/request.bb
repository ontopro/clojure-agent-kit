#!/usr/bin/env bb
;; One HTTP request to the application under test, sent as written, from INSIDE the review
;; sandbox: `bb request.bb '<json>'` with {"method" "path" "headers" "body"}, the answer printed
;; as one JSON object {"status" "headers" [[name value] ...] "body"}, or {"error" "..."}.
;;
;; A socket and HTTP/1.0, not an HTTP client: a client tidies a path (resolves `..`, refuses a
;; bad escape) and the requests a security reviewer needs are the untidy ones. HTTP/1.0 also keeps the
;; answer unchunked and the connection closed. The target is the loopback port the application
;; was started on; nothing else is reachable from the sandbox. The caller (the harness) has
;; already refused a request that is more than one, so this script only sends.

(require '[cheshire.core :as json]
         '[clojure.string :as str])
(import '(java.net Socket InetSocketAddress))

(def port (or (some-> (System/getenv "REVIEW_APP_PORT") parse-long) 8000))
(def max-answer (* 512 1024))

(defn answer [^bytes raw]
  (let [text (String. raw "ISO-8859-1")
        split (str/index-of text "\r\n\r\n")
        head (if split (subs text 0 split) text)
        body (if split (String. raw (int (+ split 4)) (int (- (alength raw) split 4)) "UTF-8") "")
        [status-line & header-lines] (str/split-lines head)]
    {:status (some-> (re-find #"^HTTP/\d\.\d (\d{3})" status-line) second parse-long)
     :headers (vec (for [l header-lines :let [[k v] (str/split l #":" 2)] :when v]
                     [(str/lower-case (str/trim k)) (str/trim v)]))
     :body body}))

(defn send! [{:strs [method path headers body]}]
  (with-open [s (doto (Socket.)
                  (.connect (InetSocketAddress. "127.0.0.1" (int port)) 5000)
                  (.setSoTimeout 15000))]
    (let [payload (some-> body not-empty (.getBytes "UTF-8"))
          head (str method " " path " HTTP/1.0\r\n"
                    "Host: localhost:" port "\r\n"
                    "User-Agent: kit-security-review\r\n"
                    "Accept: */*\r\n"
                    (str/join (for [[k v] headers] (str k ": " v "\r\n")))
                    (when payload (str "Content-Length: " (alength payload) "\r\n"))
                    "Connection: close\r\n\r\n")
          out (.getOutputStream s)
          buf (java.io.ByteArrayOutputStream.)
          chunk (byte-array 8192)]
      (.write out (.getBytes head "ISO-8859-1"))
      (when payload (.write out ^bytes payload))
      (.flush out)
      (let [in (.getInputStream s)]
        (loop []
          (let [n (.read in chunk)]
            (when (and (pos? n) (< (.size buf) max-answer))
              (.write buf chunk 0 n)
              (recur)))))
      (answer (.toByteArray buf)))))

(println
 (json/generate-string
  (try (send! (json/parse-string (first *command-line-args*)))
       (catch Exception e {:error (str (.getSimpleName (class e)) ": " (ex-message e))}))))
