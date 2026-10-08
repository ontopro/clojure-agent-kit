#!/usr/bin/env bb
;; The security checks, tried against the running application from outside, as a visitor would
;; reach it. Run from the application's folder:
;;
;;   bb --config <kit>/tools/security/bb.edn check [--serve "<cmd>"] [--health <path>] [--base <url>]
;;        [--running] [--routes <file>] [--out <dir>] [--record <file>]
;;
;; Defaults: `bb serve`, `/health`, `http://localhost:8000` - the Stack Lite template's - and
;; the routes file's defaults (`security_check.clj` has the judgements, README.md the rows).
;; `--running` probes the application already serving instead of starting one; the serving is
;; the browser pack's `server.clj`, loaded from beside this pack, not a copy of it.
;;
;; EVERY REQUEST IS SENT AS WRITTEN. An HTTP client tidies a path - resolves `..`, refuses a
;; bad escape - and the paths that matter here are exactly the untidy ones, so this speaks
;; HTTP/1.0 over a socket (a TLS socket for https, the certificate and the host name checked as
;; a browser checks them). HTTP/1.0 also keeps the answer unchunked and the connection closed.

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp]
         '[babashka.process :as p]
         '[clojure.string :as str])
(import '(java.net Socket URI InetSocketAddress)
        '(javax.net.ssl SSLSocketFactory SSLSocket SSLSession))

(def pack-dir (str (fs/parent (fs/absolutize *file*))))
(load-file (str pack-dir "/src/security_check.clj"))
(alias 'sc 'security-check)

;; ---------------------------------------------------------------------------
;; flags, and the routes file
;; ---------------------------------------------------------------------------

(defn parse-args [args]
  (loop [[a & more] args m {:running? false :env {}}]
    (cond (nil? a) m
          (= a "--running") (recur more (assoc m :running? true))
          (= a "--serve") (recur (rest more) (assoc-in m [:env "SERVE_CMD"] (first more)))
          (= a "--health") (recur (rest more) (assoc-in m [:env "HEALTH_PATH"] (first more)))
          (= a "--base") (recur (rest more) (assoc-in m [:env "APP_BASE"] (first more)))
          (= a "--routes") (recur (rest more) (assoc m :routes (first more)))
          (= a "--out") (recur (rest more) (assoc m :out (str (fs/absolutize (first more)))))
          (= a "--record") (recur (rest more) (assoc m :record (str (fs/absolutize (first more)))))
          :else (do (println "unknown argument:" a) (System/exit 2)))))

(def opts (parse-args *command-line-args*))

(def base (or (get-in opts [:env "APP_BASE"]) (System/getenv "APP_BASE") "http://localhost:8000"))
(def uri (URI. base))
(def https? (= "https" (.getScheme uri)))
(def host (.getHost uri))
(def port (let [p (.getPort uri)] (if (pos? p) p (if https? 443 80))))

(def route-defaults
  "What the probe tries when the routes file does not say: the Stack Lite template's static
  folder, no route needing a login, no form route (so `/` is tried, and a 404 or 405 there is a
  skip), no route made to throw, and a missing CSP a warn."
  {:protected [] :login nil :forms [] :throws [] :static ["/assets/"] :static-files [] :csp :optional})

(def routes
  (merge route-defaults
         (when-let [f (:routes opts)]
           (when-not (fs/exists? f) (println "no routes file at" f) (System/exit 2))
           (edn/read-string (slurp f)))))

(defn out-dir
  "Where the record goes when nothing says: a KIT workspace's `work/security/<app>/` when the
  current folder is an application in one (scratch, in no repository); else `.local/security/`.
  `--out`, else SECURITY_OUT, says otherwise."
  []
  (or (:out opts) (System/getenv "SECURITY_OUT")
      (let [here (fs/canonicalize ".") parent (fs/parent here)]
        (if (and parent (fs/exists? (fs/path parent "workspace.edn")))
          (str (fs/path parent "work" "security" (fs/file-name here)))
          (str (fs/absolutize ".local/security"))))))

;; ---------------------------------------------------------------------------
;; one request, as written
;; ---------------------------------------------------------------------------

(defn- open-socket ^Socket []
  (if https?
    (let [^SSLSocket s (.createSocket (SSLSocketFactory/getDefault))
          params (.getSSLParameters s)]
      (.setEndpointIdentificationAlgorithm params "HTTPS")
      (.setSSLParameters s params)
      (.connect s (InetSocketAddress. host (int port)) 5000)
      (.setSoTimeout s 15000)
      (.startHandshake s)
      s)
    (doto (Socket.)
      (.connect (InetSocketAddress. host (int port)) 5000)
      (.setSoTimeout 15000))))

(defn- parse-response [^bytes raw]
  (let [text (String. raw "ISO-8859-1")
        split (str/index-of text "\r\n\r\n")
        head (if split (subs text 0 split) text)
        body-bytes (if split (java.util.Arrays/copyOfRange raw (int (+ split 4)) (alength raw)) (byte-array 0))
        [status-line & header-lines] (str/split-lines head)]
    {:status (some-> (re-find #"^HTTP/\d\.\d (\d{3})" status-line) second parse-long)
     :headers (vec (for [l header-lines :let [[k v] (str/split l #":" 2)] :when v]
                     [(str/lower-case (str/trim k)) (str/trim v)]))
     :body (String. ^bytes body-bytes "UTF-8")}))

(defn request
  "`method` `path` sent exactly as given; the response read to the end, the body cut at 512 KB."
  ([method path] (request method path nil))
  ([method path {:keys [body content-type]}]
   (try
     (with-open [s (open-socket)]
       (let [payload (some-> body (.getBytes "UTF-8"))
             head (str method " " path " HTTP/1.0\r\n"
                       "Host: " host (when-not (#{80 443} port) (str ":" port)) "\r\n"
                       "User-Agent: kit-security-check\r\n"
                       "Accept: text/html,*/*\r\n"
                       (when payload (str "Content-Type: " content-type "\r\nContent-Length: " (alength payload) "\r\n"))
                       "Connection: close\r\n\r\n")
             out (.getOutputStream s)]
         (.write out (.getBytes head "ISO-8859-1"))
         (when payload (.write out ^bytes payload))
         (.flush out)
         (let [buf (java.io.ByteArrayOutputStream.)
               in (.getInputStream s)
               chunk (byte-array 8192)]
           (loop []
             (let [n (.read in chunk)]
               (when (and (pos? n) (< (.size buf) (* 512 1024)))
                 (.write buf chunk 0 n)
                 (recur))))
           (assoc (parse-response (.toByteArray buf)) :method method :path path))))
     (catch Exception e {:method method :path path :error (str (.getSimpleName (class e)) ": " (ex-message e))}))))

(defn tls
  "The handshake alone, for its own row: the protocol agreed, or why there was none."
  []
  (when https?
    (try (with-open [^SSLSocket s (open-socket)]
           {:protocol (.getProtocol ^SSLSession (.getSession s))})
         (catch Exception e {:error (ex-message e)}))))

;; ---------------------------------------------------------------------------
;; the probe
;; ---------------------------------------------------------------------------

(defn static-file
  "A file under one of the static folders, to see the headers a static answer carries: the
  routes file's `:static-files`, else the first one the home page links to."
  [home]
  (or (first (:static-files routes))
      (some (fn [dir] (some->> (re-seq (re-pattern (str "(?:href|src)=\"(" (java.util.regex.Pattern/quote dir) "[^\"?#]+)")) (str (:body home)))
                               first second))
            (:static routes))))

(defn traversals
  "Paths out of a static folder, each a way a server has been seen to let one through: the
  dots as written, the dots escaped, the slash escaped. The targets are files an application
  folder has - its configuration and its dependencies - not a guess at the machine's."
  [dir]
  (for [p ["../config.edn" "../../deps.edn" "%2e%2e/config.edn" "%2e%2e/%2e%2e/deps.edn" "..%2f..%2fdeps.edn"]]
    (str dir p)))

(defn probe
  "Every request the rows judge, in the shape `security-check/rows` takes."
  []
  (let [home (request "GET" "/")
        static (static-file home)]
    {:pages (cond-> [home] static (conj (request "GET" static)))
     :not-found (request "GET" (str "/kit-security-check-" (subs (str (random-uuid)) 0 8)))
     :malformed (request "GET" "/%zz%")
     :throws (mapv #(request "GET" %) (:throws routes))
     :forms (if (seq (:forms routes))
              (mapv (fn [p] [(request "POST" p {:body "kit-security-check=1" :content-type "application/x-www-form-urlencoded"}) false])
                    (:forms routes))
              [[(request "POST" "/" {:body "kit-security-check=1" :content-type "application/x-www-form-urlencoded"}) true]])
     :protected (mapv #(request "GET" %) (:protected routes))
     :listings (mapv #(request "GET" %) (:static routes))
     :traversals (vec (mapcat (fn [dir] (map #(request "GET" %) (traversals dir))) (:static routes)))
     :tls (tls)}))

(defn kit-commit []
  (let [r (p/shell {:dir pack-dir :out :string :err :string :continue true} "git" "rev-parse" "--short" "HEAD")]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn run-checks! []
  (let [rows (sc/rows (probe) routes https?)
        counts (sc/counts rows)
        record {:security/at (str (java.time.LocalDateTime/now (java.time.ZoneId/systemDefault)))
                :base base
                :routes (:routes opts)
                :kit (kit-commit)
                :counts counts
                :ok? (zero? (:fail counts))
                :rows rows}
        dir (out-dir)
        edn-text (str ";; Written by the KIT's security pack (`tools/security/`). Do not edit; run it again.\n"
                      (with-out-str (pp/pprint record)))]
    (println (str "\n  security checks against " base "\n"))
    (println (sc/table rows))
    (fs/create-dirs dir)
    (spit (str (fs/path dir "security.edn")) edn-text)
    (spit (str (fs/path dir "security.md")) (sc/markdown {:base base :at (:security/at record)} rows))
    (when-let [f (:record opts)]
      (some-> (fs/parent f) fs/create-dirs)
      (spit f edn-text))
    (println (format "\n  %d ok, %d warn, %d fail, %d skipped - %s/security.md%s"
                     (:ok counts) (:warn counts) (:fail counts) (:skipped counts) dir
                     (if-let [f (:record opts)] (str ", recorded to " f) "")))
    (:ok? record)))

;; three top-level forms, as in the browser pack: the server file is loaded before the form
;; that names `with-server` is read, and the settings are in place before the file reads them
(doseq [[k v] (:env opts)] (System/setProperty (str "browser-check." k) v))
(load-file (str pack-dir "/../browser/server.clj"))
(when-not (if (:running? opts) (run-checks!) (with-server run-checks!))
  (System/exit 1))
