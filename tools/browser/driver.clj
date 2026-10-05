;; The driver the browser scripts share: Etaoin under Babashka, driving a headless Firefox
;; through geckodriver. No Node and no npm. Loaded with `load-file` by the scripts beside it, and
;; by a project's own interaction checks (`(load-file "<kit>/tools/browser/driver.clj")`); it is
;; not a namespace of any application and knows nothing of any framework.
;;
;; Three things to know, each learnt on a real project:
;;
;;   THE PERMISSION IS THE TERMINAL APPLICATION'S. macOS grants the right to use Firefox to the
;;   application that asks - the terminal you type in. A shell under a daemon gets no question
;;   and a silent refusal: Firefox starts, never launches its content process, never writes its
;;   port, and geckodriver gives up after 60 s. `geckodriver --version` passes all the same;
;;   only a real start shows it. Run the checks from a terminal once, and answer the prompt.
;;
;;   A SCREENSHOT IS OF THE VIEWPORT. For a whole page the window is made as tall as the page
;;   first (`fit-height!`).
;;
;;   KEY ACTIONS LEAK BETWEEN CHECKS. A key held by one check has been seen to change the
;;   focus behaviour of the next even after `release-actions`, so `open-page` releases and goes
;;   through a blank page, and a script with interaction checks runs each in a browser of its own.

(require '[babashka.deps :as deps])
;; here as well as in the pack's bb.edn, so a script run on its own, or this file loaded from a
;; project's script, has the dependency too
(deps/add-deps '{:deps {etaoin/etaoin {:mvn/version "1.1.43"}}})
(require '[etaoin.api :as e]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

;; Etaoin logs every WebDriver call at DEBUG, and under Babashka that reaches the console.
;; Warnings and errors still come through; BROWSER_LOG=debug puts the calls back for a check
;; that needs diagnosing.
(require '[taoensso.timbre :as timbre])
(timbre/set-min-level! (keyword (or (System/getenv "BROWSER_LOG") "warn")))

(def base
  "Where the application answers: APP_BASE, else the Stack Lite template's default."
  (or (System/getenv "APP_BASE") "http://localhost:8000"))

(defn- default-out
  "Where the scripts write when nothing says: a KIT workspace's `work/browser/<app>/` when the
  current folder is an application in one (`work/` is scratch in no repository, which is what
  these are); else `.local/browser/` here."
  []
  (let [here (fs/canonicalize ".")
        parent (fs/parent here)]
    (if (and parent (fs/exists? (fs/path parent "workspace.edn")))
      (str (fs/path parent "work" "browser" (fs/file-name here)))
      ".local/browser")))

(def out-dir
  "Where the scripts write. BROWSER_OUT overrides."
  (or (System/getenv "BROWSER_OUT") (default-out)))

(def geckodriver
  "The WebDriver for Firefox: GECKODRIVER, or the one on the PATH."
  (or (System/getenv "GECKODRIVER") (some-> (fs/which "geckodriver") str)))

(def firefox
  "The browser: FIREFOX, the one on the PATH, or where the installer puts it."
  (or (System/getenv "FIREFOX")
      (some-> (fs/which "firefox") str)
      (first (filter fs/exists? ["/Applications/Firefox.app/Contents/MacOS/firefox"
                                 "/usr/bin/firefox" "/usr/local/bin/firefox" "/snap/bin/firefox"]))))

(defn require-browser!
  "Stops, saying what is missing, when the checks cannot run here."
  []
  (when-not (and geckodriver firefox)
    (println (str "the browser checks need geckodriver and Firefox: "
                  (if geckodriver "geckodriver found" "no geckodriver on the PATH (set GECKODRIVER)") ", "
                  (if firefox "Firefox found" "no Firefox found (set FIREFOX to its binary)")))
    (System/exit 2)))

(defn with-firefox
  "Runs `f` with a headless Firefox of `width` x `height`, on a profile of its own, and quits it."
  [[width height] f]
  (require-browser!)
  (let [profile (str (fs/create-temp-dir {:prefix "browser-check-"}))
        d (try (e/firefox {:path-driver geckodriver
                           :path-browser firefox
                           :args ["--headless" "--no-remote" "-profile" profile]
                           :size [width height]})
               (catch Exception ex
                 (println "Firefox did not start:" (ex-message ex))
                 (println "If this is a shell under a daemon on macOS, the terminal application has not been"
                          "granted the permission to use Firefox; run once from a terminal and answer the prompt.")
                 (throw ex)))]
    (try (f d)
         (finally (e/quit d) (fs/delete-tree profile)))))

(defn open-page
  "Opens `path` under `base` and waits until the document is complete and, where the page
  loads them, HTMX and Alpine have initialised."
  [d path]
  (try (e/release-actions d) (catch Exception _ nil))
  (e/go d "about:blank")
  (e/go d (str base path))
  (e/wait-visible d {:tag :body} {:timeout 20})
  (e/wait-predicate
   #(e/js-execute d (str "if (document.readyState !== 'complete') return false;"
                         "var wantsHtmx = !!document.querySelector('script[src*=\"htmx\"]');"
                         "var wantsAlpine = !!document.querySelector('script[src*=\"alpine\"]');"
                         "return (!wantsHtmx || !!window.htmx) && (!wantsAlpine || !!window.Alpine);"))
   {:timeout 10 :interval 0.1}))

(defn measure
  "What the viewport and the document measure. An overflow shows as scrollWidth > innerWidth."
  [d]
  (e/js-execute d (str "return {innerWidth: window.innerWidth, innerHeight: window.innerHeight,"
                       " scrollWidth: document.documentElement.scrollWidth,"
                       " scrollHeight: document.documentElement.scrollHeight}")))

(defn fit-height!
  "Makes the window as tall as the page, so one screenshot holds all of it; returns the measure
  after. 85 px is Firefox's chrome above the viewport; 12000 a ceiling a screenshot survives."
  [d width]
  (let [m (measure d)
        h (max 600 (min 12000 (+ 85 (:scrollHeight m))))]
    (e/set-window-size d {:width width :height h})
    (e/wait 0.3)
    (measure d)))

(defn slug-of
  "A file name for a path: `/` is home, `/pages/my-page` is my-page."
  [path]
  (let [s (str/replace (str/replace path #"^/|/$" "") #"[^A-Za-z0-9._-]+" "-")]
    (if (str/blank? s) "home" s)))

(defn out [file-name] (str out-dir "/" file-name))
(defn ensure-out! [] (fs/create-dirs out-dir))
(defn now [] (str (java.time.ZonedDateTime/now)))
