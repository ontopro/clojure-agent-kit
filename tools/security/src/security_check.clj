(ns security-check
  "The security pack's judgements: each a pure function from the responses the probe got to
  rows of the table. Nothing here makes a request; `check.bb` beside this folder probes and
  hands the answers over, so a test gives canned responses and reads the rows.

  A response is `{:path :method :status :headers [[name value] ...] :body}`, names in lower
  case, a header that came twice twice; or `{:path :method :error \"...\"}` when no answer came.
  A row is `{:check :status :subject :says :fix}`, `:status` one of `:ok`, `:warn`, `:fail` and
  `:skipped`; only a `:fail` makes the run fail, and a warn or a skip says why in `:says`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; what fixes a row, in the pinned template's terms
;; ---------------------------------------------------------------------------

(def fixes
  "What fixes a failing or warned row, said in the terms of the KIT's pinned template
  (`kit-v1.1`) where the fix is the template's, and as the project's decision where it is not.
  An application on another framework reads the template's line as what to look for."
  {:headers "kit-v1.1 sets them in `server.clj` `ring-handler`, the handler-wide `:middleware` (nosniff, frame options, HSTS, X-XSS-Protection) and `wrap-referrer-policy`; one missing from a page not found or a static file means that middleware moved into the router's route data, which those responses never pass"
   :csp "02-architecture §8 decides it; kit-v1.1 sets none - a policy is a middleware beside `wrap-referrer-policy` in `server.clj` `ring-handler`, loose enough for the Alpine and htmx builds the template ships"
   :server "`server.clj` `ig/init-key ::server`: `jetty/run-jetty` takes `:send-server-version? false`; kit-v1.1 leaves Jetty's default (02-architecture §4, not given)"
   :cookies "`server.clj` `ring-handler`, `wrap-session`'s `:cookie-attrs`: kit-v1.1 sets HttpOnly and SameSite=Lax, and Secure from `config.edn` `:cookie-attrs-secure?` under `:prod`; a cookie the application sets itself carries the same attributes by hand"
   :csrf "`server.clj` `ring-handler`, `anti-forgery/wrap-anti-forgery` in the route middleware: a route that takes a POST without the token is outside it, or answers before it"
   :protected "the project's own auth (02-architecture §8): the route refuses an unauthenticated request before its handler runs; kit-v1.1 generates no accounts"
   :errors "`server.clj` `exception-middleware`, first of the route middleware in kit-v1.1, answers the error page and logs the detail; `ring/create-default-handler` answers a page not found; a page that names the error is a handler or a middleware ahead of them"
   :static "`server.clj` `ring-handler`, `reitit-extras/create-resource-handler-cached` serves `resources/public/` under `/assets/` with no listing and no path out; a second handler over a folder of files is what lists or escapes"
   :tls "where TLS ends (02-architecture §8): kit-v1.1's Kamal deploy ends it in the proxy (`.kamal/deploy.yml`, `proxy`); its certificate and protocols are the proxy's"})

(defn row
  "A row of the table; the fix only where there is something to fix."
  [check status subject says]
  (cond-> {:check check :status status :subject subject :says says}
    (#{:fail :warn} status) (assoc :fix (fixes check))))

;; ---------------------------------------------------------------------------
;; reading a response
;; ---------------------------------------------------------------------------

(defn header-values
  "Every value of the header `nm` (lower case) on the response, in the order they came."
  [response nm]
  (keep (fn [[k v]] (when (= k nm) v)) (:headers response)))

(defn header
  "The header's value, the values joined where it came more than once; nil when absent."
  [response nm]
  (some->> (seq (header-values response nm)) (str/join ", ")))

(defn answered
  "The responses that came, without the requests that got no answer."
  [responses]
  (remove :error responses))

(defn- label [{:keys [method path]}]
  (str (if (= "GET" method) "" (str method " ")) path))

(defn- labels [responses]
  (str/join ", " (map label responses)))

;; ---------------------------------------------------------------------------
;; headers, on every response the probe got
;; ---------------------------------------------------------------------------

(defn csp-directives
  "A Content-Security-Policy as `{directive [source ...]}`, directives lower-cased."
  [policy]
  (into {}
        (for [part (str/split (or policy "") #";")
              :let [[d & sources] (str/split (str/trim part) #"\s+")]
              :when (not (str/blank? d))]
          [(str/lower-case d) (vec sources)])))

(defn- per-response
  "One row for a header rule over every response: ok when each passes, else the rule's
  status naming the responses that did not. `bad` is a response -> reason, nil when it passes.
  A miss only on an ODD request - one a browser never sends, which a server may refuse before
  the application sees it, with a page of its own - is a warn whatever the rule's status: the
  page is the server's, it is not one a link leads to, and the rule is about the application's."
  [check status responses bad ok-says]
  (let [misses (keep (fn [r] (when-let [why (bad r)] [r why])) responses)]
    (cond
      (empty? misses) (row check :ok (str "all " (count responses) " responses") ok-says)
      (every? (comp :odd? first) misses)
      (row check :warn (labels (map first misses))
           (str (str/join "; " (distinct (map second misses)))
                " - only on requests a browser never sends, answered before the application"))
      :else (row check status (labels (map first (remove (comp :odd? first) misses)))
                 (str/join "; " (distinct (map second misses)))))))

(defn header-rows
  "The response headers every answer should carry: nosniff, frame options (or a CSP
  frame-ancestors), a Referrer-Policy that is not `unsafe-url`, HSTS where the base is https,
  and X-XSS-Protection absent or `0` - a `1` turns on what is left of an old filter, a warn."
  [responses https?]
  (let [rs (answered responses)]
    (cond->
     [(per-response :headers :fail rs
                    #(when-not (= "nosniff" (some-> (header % "x-content-type-options") str/lower-case))
                       "no X-Content-Type-Options: nosniff")
                    "X-Content-Type-Options: nosniff")
      (per-response :headers :fail rs
                    #(when-not (or (#{"deny" "sameorigin"} (some-> (header % "x-frame-options") str/lower-case))
                                   (contains? (csp-directives (header % "content-security-policy")) "frame-ancestors"))
                       "no X-Frame-Options DENY or SAMEORIGIN, and no frame-ancestors")
                    (str "frame options: " (or (some #(header % "x-frame-options") rs) "frame-ancestors")))
      (per-response :headers :fail rs
                    #(let [v (some-> (header % "referrer-policy") str/lower-case)]
                       (cond (nil? v) "no Referrer-Policy"
                             (str/includes? v "unsafe-url") "Referrer-Policy: unsafe-url sends the whole URL away"))
                    (str "Referrer-Policy: " (some #(header % "referrer-policy") rs)))
      (per-response :headers :warn rs
                    #(when (some-> (header % "x-xss-protection") str/trim (str/starts-with? "1"))
                       "X-XSS-Protection: 1 turns on an old browser's filter, which could strip a page's own scripts; 0 or none")
                    "X-XSS-Protection absent or 0")]
      https? (conj (per-response :headers :fail rs
                                 #(when-not (re-find #"max-age=[1-9]" (str (header % "strict-transport-security")))
                                    "no Strict-Transport-Security with a max-age")
                                 "Strict-Transport-Security with a max-age")))))

(defn csp-row
  "The Content-Security-Policy. Absent: a warn, since the template sets none and 02 §8 decides
  it - or a fail when the routes file says `:csp :required`, which is that decision made.
  Present: a fail when a script may come from anywhere (`*`, or a bare scheme), in
  `script-src` or in the `default-src` it falls back to; the policy otherwise is the
  project's, and the row says what it allows."
  [responses required?]
  (let [rs (answered responses)
        missing (remove #(header % "content-security-policy") rs)
        loose (fn [r]
                (let [ds (csp-directives (header r "content-security-policy"))
                      scripts (get ds "script-src" (get ds "default-src"))]
                  (cond (nil? scripts) "no script-src and no default-src: scripts from anywhere"
                        (some #{"*" "http:" "https:" "data:"} scripts)
                        (str "scripts from anywhere: " (str/join " " scripts)))))
        bad (keep (fn [r] (when-let [why (loose r)] [r why])) (remove (set missing) rs))]
    (cond
      (seq bad) (row :csp :fail (labels (map first bad)) (str/join "; " (distinct (map second bad))))
      (seq missing) (row :csp (if required? :fail :warn) (if (= (count missing) (count rs)) "every response" (labels missing))
                         (str "no Content-Security-Policy"
                              (if required? " - the routes file says :csp :required" " - 02-architecture §8 decides one")))
      :else (row :csp :ok (str "all " (count rs) " responses")
                 (let [p (header (first rs) "content-security-policy")]
                   (str "present" (when (str/includes? p "unsafe-") " (with unsafe- sources: the project's choice in 02 §8)")))))))

(defn server-row
  "A Server header that names a version tells a visitor which advisories to try: a warn."
  [responses]
  (let [vs (distinct (keep #(header % "server") (answered responses)))]
    (if-let [v (first (filter #(re-find #"\d" %) vs))]
      (row :server :warn "Server" (str "Server: " v " names its version"))
      (row :server :ok "Server" (if (seq vs) (str "Server: " (str/join ", " vs) ", no version") "no Server header")))))

;; ---------------------------------------------------------------------------
;; cookies
;; ---------------------------------------------------------------------------

(defn parse-cookie
  "A Set-Cookie value as `{:name :attrs #{lower-case attribute names} :same-site value}`."
  [s]
  (let [[pair & attrs] (map str/trim (str/split s #";"))
        kv (into {} (for [a attrs :let [[k v] (str/split a #"=" 2)]] [(str/lower-case k) v]))]
    {:name (first (str/split pair #"=" 2))
     :attrs (set (keys kv))
     :same-site (some-> (get kv "samesite") str/lower-case)}))

(defn cookie-rows
  "Every cookie set on any response: HttpOnly, a SameSite (and `None` only with Secure), and
  Secure where the base is https. One row per cookie name. None set: one skipped row, said."
  [responses https?]
  (let [cookies (->> (answered responses)
                     (mapcat #(header-values % "set-cookie"))
                     (map parse-cookie)
                     (group-by :name))]
    (if (empty? cookies)
      [(row :cookies :skipped "Set-Cookie" "no response set a cookie")]
      (vec (for [[nm cs] (sort-by key cookies)
                 :let [c (first cs)
                       secure? (contains? (:attrs c) "secure")
                       misses (cond-> []
                                (not (contains? (:attrs c) "httponly")) (conj "no HttpOnly")
                                (nil? (:same-site c)) (conj "no SameSite")
                                (and (= "none" (:same-site c)) (not secure?)) (conj "SameSite=None without Secure")
                                (and https? (not secure?)) (conj "no Secure over https"))]]
             (if (seq misses)
               (row :cookies :fail nm (str/join "; " misses))
               (row :cookies :ok nm (str "HttpOnly, SameSite=" (:same-site c) (when secure? ", Secure")))))))))

;; ---------------------------------------------------------------------------
;; CSRF, the route list, errors, static files
;; ---------------------------------------------------------------------------

(defn csrf-row
  "A POST without a token to a form route: refused (400, 401, 403, 419, 422) is ok; accepted
  or redirected is a fail; an error is a fail. The default route `/` - tried when the routes
  file names no form - answering 404 or 405 takes no POST at all, so nothing was tried: a skip
  that says to name one. A NAMED form route answering 404 or 405 means the list is wrong."
  [response default?]
  (let [{:keys [status error]} response
        subject (label response)]
    (cond
      error (row :csrf :fail subject (str "no answer: " error))
      (#{400 401 403 419 422} status) (row :csrf :ok subject (str status ", refused without the token"))
      (and default? (#{404 405} status))
      (row :csrf :skipped subject (str status ": this route takes no POST, so nothing was tried - name the application's form routes under :forms"))
      (#{404 405} status) (row :csrf :fail subject (str status ": the routes file names a form route that takes no POST - the list is wrong"))
      (<= 200 status 399) (row :csrf :fail subject (str status ": accepted without the token"))
      :else (row :csrf :fail subject (str status ": an error, not a refusal")))))

(defn protected-row
  "A route the routes file says needs a login, asked without one: 401 or 403, or a redirect
  to the login (any redirect when no login path is named). 2xx is a fail - the page is open -
  and so is a 404: the list names a route the application does not have."
  [response login]
  (let [{:keys [status error]} response
        location (header response "location")
        subject (label response)]
    (cond
      error (row :protected :fail subject (str "no answer: " error))
      (#{401 403} status) (row :protected :ok subject (str status ", refused"))
      (and (<= 300 status 399) (or (nil? login) (str/includes? (str location) login)))
      (row :protected :ok subject (str status " to " location))
      (<= 300 status 399) (row :protected :fail subject (str status " to " location ", not to the login " login))
      (<= 200 status 299) (row :protected :fail subject (str status ": answered without a login"))
      (= 404 status) (row :protected :fail subject "404: the routes file names a route the application does not have - the list is wrong")
      :else (row :protected :fail subject (str status ": neither a refusal nor a redirect to the login")))))

(def leaks
  "What an error page must not carry, as [pattern what-it-is]: a stack frame, a compiled
  Clojure function's class (`ns$fn`), a source file and line, an exception's class name, an
  absolute path on a machine."
  [[#"\bat [\w.$]+\([\w.]+\.(?:java|clj|cljc):\d+\)" "a stack frame"]
   [#"\b[a-z][\w-]*(?:\.[\w-]+)+\$[\w-]+" "a compiled function's class"]
   [#"\b[\w-]+\.(?:clj|cljc|java):\d+" "a source file and line"]
   [#"\b(?:java|clojure|javax|jakarta|org\.eclipse\.jetty)\.[\w.]+\.[A-Z]\w+" "a class name"]
   [#"\b\w*Exception(?:Info)?\b" "an exception's class"]
   [#"(?:/Users/|/home/|/root/|/var/folders/|[A-Z]:\\\\)\S+" "a path on a machine"]])

(defn leak
  "What the body names that an error page must not: [what-it-is the-text], or nil."
  [body]
  (some (fn [[re what]] (when-let [m (re-find re (str body))] [what (if (string? m) m (first m))]))
        leaks))

(defn error-row
  "An error answer - a page not found, a malformed request, a route the project makes throw -
  carries nothing of the error: no trace, class, file or path. Its status is said, not judged,
  except that a page that does not exist answering 200 is a warn (a catch-all route)."
  [response kind]
  (let [{:keys [status body error]} response
        subject (label response)]
    (cond
      error (row :errors :fail subject (str kind ": no answer - " error))
      :else
      (if-let [[what text] (leak body)]
        (row :errors :fail subject (str kind ", " status ": the page carries " what ": " (subs text 0 (min 80 (count text)))))
        (if (and (= kind "a page that does not exist") (<= 200 status 299))
          (row :errors :warn subject (str kind " answered " status " - a catch-all route; nothing of an error in it"))
          (row :errors :ok subject (str kind ", " status ", nothing of the error in the page")))))))

(defn listing?
  "Whether a body reads as a directory listing."
  [body]
  (boolean (re-find #"(?i)<title>\s*(?:index of|directory listing)|\bindex of /|parent directory" (str body))))

(defn listing-row
  "A static folder asked for itself: a listing is a fail."
  [response]
  (let [{:keys [status body error]} response
        subject (label response)]
    (cond error (row :static :fail subject (str "no answer: " error))
          (and (<= 200 status 299) (listing? body)) (row :static :fail subject (str status ": lists the folder"))
          :else (row :static :ok subject (str status ", no listing")))))

(defn traversal-row
  "A path out of a static folder: a file coming back (2xx, not an HTML page) is a fail; a page
  coming back is a warn - a catch-all route answered, nothing escaped that the probe can see."
  [response]
  (let [{:keys [status error]} response
        ctype (str (header response "content-type"))
        subject (label response)]
    (cond error (row :static :fail subject (str "no answer: " error))
          (and (<= 200 status 299) (not (str/includes? ctype "text/html")))
          (row :static :fail subject (str status " " ctype ": a file from outside the folder came back"))
          (<= 200 status 299) (row :static :warn subject (str status ": a page answered a path out of the folder (a catch-all route?)"))
          :else (row :static :ok subject (str status ", refused")))))

(defn tls-row
  "The handshake: `{:protocol \"TLSv1.3\"}`, or `{:error ...}` when the certificate did not
  validate or no protocol was agreed; nil when the base is not https."
  [tls]
  (cond (nil? tls) (row :tls :skipped "TLS" "the base is http; TLS is tried on an https base")
        (:error tls) (row :tls :fail "TLS" (str "the handshake failed: " (:error tls)))
        (#{"TLSv1.2" "TLSv1.3"} (:protocol tls)) (row :tls :ok "TLS" (str "the certificate validates; " (:protocol tls)))
        :else (row :tls :fail "TLS" (str (:protocol tls) " - older than TLS 1.2"))))

;; ---------------------------------------------------------------------------
;; the whole table, and its renderings
;; ---------------------------------------------------------------------------

(defn rows
  "Every row, from what the probe got: `probes` is `{:pages [resp] :not-found resp :malformed
  resp :throws [resp] :forms [[resp default?]] :protected [resp] :listings [resp] :traversals
  [resp] :tls tls-or-nil}`; `routes` the routes file merged over its defaults; `https?` the
  base's scheme. The header rows read every response the probe got."
  [{:keys [pages not-found malformed throws forms protected listings traversals tls]} routes https?]
  (let [all (concat pages [not-found] throws (map first forms) protected listings
                    (map #(assoc % :odd? true) (cons malformed traversals)))]
    (vec (concat (header-rows all https?)
                 [(csp-row all (= :required (:csp routes)))
                  (server-row all)]
                 (cookie-rows all https?)
                 (map (fn [[r default?]] (csrf-row r default?)) forms)
                 (if (seq protected)
                   (map #(protected-row % (:login routes)) protected)
                   [(row :protected :skipped ":protected" "the routes file names no route that needs a login")])
                 [(error-row not-found "a page that does not exist")
                  (error-row malformed "a malformed request")]
                 (map #(error-row % "a route the project makes throw") throws)
                 (map listing-row listings)
                 (map traversal-row traversals)
                 [(tls-row tls)]))))

(defn counts
  "How many rows of each status."
  [rows]
  (merge {:ok 0 :warn 0 :fail 0 :skipped 0} (frequencies (map :status rows))))

(defn table
  "The rows as the terminal's table: check, status, subject, what it says; a fix under each
  failing or warned row."
  [rows]
  (str/join "\n"
            (for [{:keys [check status subject says fix]} rows]
              (str (format "  %-9s %-7s %-34s %s" (name check) (name status) subject says)
                   (when fix (str "\n" (format "  %-9s %-7s %-34s fix: %s" "" "" "" fix)))))))

(defn markdown
  "The rows as a Markdown table, the record's companion for a person to read."
  [{:keys [base at] :as _record} rows]
  (str "# Security checks - " base " - " at "\n\n"
       "| Check | Status | Subject | Says | Fix |\n|---|---|---|---|---|\n"
       (str/join "\n" (for [{:keys [check status subject says fix]} rows]
                        (str "| " (name check) " | " (name status) " | " (str/replace subject "|" "\\|")
                             " | " (str/replace says "|" "\\|") " | " (some-> fix (str/replace "|" "\\|")) " |")))
       "\n"))
