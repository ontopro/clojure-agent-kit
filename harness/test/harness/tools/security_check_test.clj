(ns harness.tools.security-check-test
  "The security pack's judgements (`tools/security/src/security_check.clj`), on canned
  responses: each row passes what it should and fails what it should. The probe itself - the
  socket, the serving - is tried against a running application, by `bb health`."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [security-check :as sc]))

(def good-headers
  [["x-content-type-options" "nosniff"]
   ["x-frame-options" "SAMEORIGIN"]
   ["referrer-policy" "strict-origin-when-cross-origin"]
   ["strict-transport-security" "max-age=31536000; includeSubDomains"]
   ["x-xss-protection" "0"]])

(defn- resp
  ([path status] (resp path status good-headers ""))
  ([path status headers body] {:method "GET" :path path :status status :headers headers :body body}))

(defn- statuses [rows] (mapv :status rows))

(deftest the-headers-pass-on-every-response-that-carries-them
  (is (every? #{:ok} (statuses (sc/header-rows [(resp "/" 200) (resp "/x" 404)] true)))))

(deftest a-header-missing-from-one-response-fails-and-names-it
  (let [bare (resp "/assets/app.css" 200 [["x-frame-options" "DENY"]] "")
        rows (sc/header-rows [(resp "/" 200) bare] false)
        nosniff (first rows)]
    (is (= :fail (:status nosniff)))
    (is (= "/assets/app.css" (:subject nosniff)))
    (is (str/includes? (:fix nosniff) "server.clj"))
    (testing "the frame row passes: DENY is a frame option"
      (is (= :ok (:status (second rows)))))))

(deftest a-miss-only-on-a-request-a-browser-never-sends-is-a-warn
  (let [odd (assoc (resp "/%zz%" 400 [] "<h2>HTTP ERROR 400</h2>") :odd? true)]
    (is (= :warn (:status (first (sc/header-rows [(resp "/" 200) odd] false)))))
    (testing "the same miss on an ordinary request fails, and names only the ordinary one"
      (let [row (first (sc/header-rows [(resp "/" 200) odd (resp "/x" 404 [] "")] false))]
        (is (= :fail (:status row)))
        (is (= "/x" (:subject row)))))))

(deftest hsts-is-asked-for-only-over-https
  (let [no-hsts (resp "/" 200 (remove #(= "strict-transport-security" (first %)) good-headers) "")]
    (is (= 4 (count (sc/header-rows [no-hsts] false))))
    (is (= :fail (:status (last (sc/header-rows [no-hsts] true)))))))

(deftest x-xss-protection-1-is-a-warn
  (is (= :warn (:status (nth (sc/header-rows [(resp "/" 200 [["x-xss-protection" "1; mode=block"]] "")] false) 3)))))

(deftest a-referrer-policy-of-unsafe-url-fails
  (is (= :fail (:status (nth (sc/header-rows [(resp "/" 200 [["referrer-policy" "unsafe-url"]] "")] false) 2)))))

(deftest the-csp-is-a-warn-when-absent-a-fail-when-required-or-open
  (is (= :warn (:status (sc/csp-row [(resp "/" 200)] false))))
  (is (= :fail (:status (sc/csp-row [(resp "/" 200)] true))))
  (is (= :fail (:status (sc/csp-row [(resp "/" 200 [["content-security-policy" "default-src *"]] "")] false))))
  (is (= :fail (:status (sc/csp-row [(resp "/" 200 [["content-security-policy" "img-src 'self'"]] "")] false)))
      "no script-src and no default-src: scripts from anywhere")
  (let [loose (sc/csp-row [(resp "/" 200 [["content-security-policy" "default-src 'self'; script-src 'self' 'unsafe-eval'"]] "")] false)]
    (is (= :ok (:status loose)))
    (is (str/includes? (:says loose) "unsafe-"))))

(deftest a-frame-ancestors-directive-stands-for-x-frame-options
  (is (= :ok (:status (second (sc/header-rows [(resp "/" 200 [["content-security-policy" "default-src 'self'; frame-ancestors 'none'"]] "")] false))))))

(deftest a-server-header-with-a-version-is-a-warn
  (is (= :warn (:status (sc/server-row [(resp "/" 200 [["server" "Jetty(12.1.0)"]] "")]))))
  (is (= :ok (:status (sc/server-row [(resp "/" 200 [["server" "Jetty"]] "")])))))

(deftest every-cookie-is-http-only-and-same-site
  (let [with-cookie (fn [c] [(resp "/" 200 [["set-cookie" c]] "")])]
    (is (= [:ok] (statuses (sc/cookie-rows (with-cookie "ring-session=abc;Path=/;HttpOnly;SameSite=Lax") false))))
    (is (= [:fail] (statuses (sc/cookie-rows (with-cookie "ring-session=abc;Path=/;SameSite=Lax") false))))
    (is (= [:fail] (statuses (sc/cookie-rows (with-cookie "ring-session=abc;Path=/;HttpOnly") false))))
    (is (= [:fail] (statuses (sc/cookie-rows (with-cookie "s=1;HttpOnly;SameSite=None") false))))
    (testing "Secure is asked for over https only"
      (is (= [:fail] (statuses (sc/cookie-rows (with-cookie "s=1;HttpOnly;SameSite=Lax") true))))
      (is (= [:ok] (statuses (sc/cookie-rows (with-cookie "s=1;HttpOnly;SameSite=Lax;Secure") true)))))
    (testing "no cookie at all is a skip that says so"
      (is (= [:skipped] (statuses (sc/cookie-rows [(resp "/" 200)] false)))))))

(deftest a-post-without-the-token-is-refused-or-the-row-says-why-not
  (let [post (fn [status] {:method "POST" :path "/contact" :status status :headers [] :body ""})]
    (is (= :ok (:status (sc/csrf-row (post 403) false))))
    (is (= :fail (:status (sc/csrf-row (post 200) false))))
    (is (= :fail (:status (sc/csrf-row (post 302) false))))
    (is (= :fail (:status (sc/csrf-row (post 500) false))))
    (testing "the default route taking no POST tried nothing: a skip; a named one, a wrong list"
      (is (= :skipped (:status (sc/csrf-row (post 405) true))))
      (is (= :fail (:status (sc/csrf-row (post 405) false)))))))

(deftest a-route-that-needs-a-login-refuses-or-redirects-to-it
  (let [get-as (fn [status location] (resp "/admin" status (cond-> [] location (conj ["location" location])) ""))]
    (is (= :ok (:status (sc/protected-row (get-as 401 nil) "/login"))))
    (is (= :ok (:status (sc/protected-row (get-as 302 "/login?next=/admin") "/login"))))
    (is (= :fail (:status (sc/protected-row (get-as 302 "/") "/login"))))
    (is (= :ok (:status (sc/protected-row (get-as 302 "/") nil))) "no login named: any redirect")
    (is (= :fail (:status (sc/protected-row (get-as 200 nil) "/login"))))
    (is (= :fail (:status (sc/protected-row (get-as 404 nil) "/login"))))))

(deftest an-error-page-names-nothing-of-the-error
  (doseq [[body what] [["at hc.handlers$home_handler.invoke(handlers.clj:12)" "a stack frame"]
                       ["{\"in\":\"hc.handlers$home_handler\"}" "a compiled function's class"]
                       ["see server.clj:91" "a source file and line"]
                       ["java.lang.IllegalStateException" "a class name"]
                       ["ExceptionInfo: boom" "an exception's class"]
                       ["/Users/someone/app/src/hc/db.clj" "a path on a machine"]]]
    (testing what
      (is (= what (first (sc/leak body))))
      (is (= :fail (:status (sc/error-row (resp "/boom" 500 [] body) "a route the project makes throw"))))))
  (testing "the template's own pages pass: a page not found, an error page, Jetty's refusal"
    (doseq [body ["<h1>Page not found</h1>" "<h1>Something went wrong</h1>"
                  "<title>Error 400 Ambiguous URI path segment</title><a href=\"https://jetty.org/\">Powered by Jetty:// 12.1.0</a>"
                  "<div x-data=\"{open: false}\" @click=\"$dispatch('x')\" x-init=\"this.$el\"></div>"]]
      (is (nil? (sc/leak body)) body)))
  (testing "a page that does not exist answering 200 is a warn"
    (is (= :warn (:status (sc/error-row (resp "/nope" 200) "a page that does not exist"))))))

(deftest a-static-folder-neither-lists-nor-lets-a-path-out
  (is (= :fail (:status (sc/listing-row (resp "/assets/" 200 [] "<title>Index of /assets/</title>")))))
  (is (= :ok (:status (sc/listing-row (resp "/assets/" 404)))))
  (is (= :fail (:status (sc/traversal-row (resp "/assets/../config.edn" 200 [["content-type" "application/edn"]] "{:db 1}")))))
  (is (= :warn (:status (sc/traversal-row (resp "/assets/../config.edn" 200 [["content-type" "text/html"]] "<html>")))))
  (is (= :ok (:status (sc/traversal-row (resp "/assets/../config.edn" 400))))))

(deftest tls-is-tried-on-https-only-and-needs-a-current-protocol
  (is (= :skipped (:status (sc/tls-row nil))))
  (is (= :ok (:status (sc/tls-row {:protocol "TLSv1.3"}))))
  (is (= :fail (:status (sc/tls-row {:protocol "TLSv1.1"}))))
  (is (= :fail (:status (sc/tls-row {:error "PKIX path validation failed"})))))

(deftest a-request-that-got-no-answer-fails-its-row
  (is (= :fail (:status (sc/csrf-row {:method "POST" :path "/" :error "Connection refused"} true))))
  (is (= :fail (:status (sc/error-row {:method "GET" :path "/x" :error "Read timed out"} "a page that does not exist")))))

(deftest the-whole-table-counts-and-only-a-fail-fails
  (let [probes {:pages [(resp "/" 200)] :not-found (resp "/nope" 404) :malformed (resp "/%zz%" 400)
                :throws [] :forms [[{:method "POST" :path "/" :status 405 :headers good-headers :body ""} true]]
                :protected [] :listings [(resp "/assets/" 404)] :traversals [(resp "/assets/../config.edn" 404)] :tls nil}
        rows (sc/rows probes {:csp :optional} false)
        counts (sc/counts rows)]
    (is (zero? (:fail counts)))
    (is (= 1 (:warn counts)) "the missing CSP")
    (is (every? #(contains? sc/fixes (:check %)) rows))
    (is (str/includes? (sc/markdown {:base "http://localhost:8000" :at "now"} rows) "| csp | warn |"))))
