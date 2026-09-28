(ns harness.setup.template
  "The template the KIT brings with it: read the pin, and build the command that
  generates an application from it.

  PURE UP TO THE EDGE. `pin` reads data and `create-command` returns an argv;
  nothing here runs a process, so what `bb init` will execute can be tested
  without a JVM, a network or a generated project.

  Generation is deps-new's `create`, run with the template and deps-new itself
  as plain dependencies (`-Sdeps` and `-X`), so an adopter installs no Clojure
  tool first. `-Srepro` keeps a personal `~/.clojure/deps.edn` out of it."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def resource-name "template-pins.edn")

(defn load-pins
  "The pins file, from the classpath or from any slurpable thing (tests)."
  ([] (load-pins (or (io/resource resource-name)
                     (throw (ex-info "template-pins.edn not on the classpath"
                                     {:resource resource-name})))))
  ([source] (edn/read-string (slurp source))))

(defn pin
  "The entry `bb init` generates from: the one named `k`, or the file's `:default`.
  Throws, naming what IS there, when the name is unknown - a typo must not fall
  back to a different template."
  ([pins] (pin pins (:default pins)))
  ([pins k]
   (or (some-> (get-in pins [:templates k]) (assoc :template/key k))
       (throw (ex-info (str "no template named " (pr-str k) " in " resource-name
                            " - it has " (pr-str (sort (keys (:templates pins)))))
                       {:template k :known (sort (keys (:templates pins)))})))))

(defn template-dep
  "How the template is put on the classpath: the pinned commit, or - for someone
  developing the template - a local working tree."
  [{:keys [git/url git/sha]} local-root]
  (if local-root
    {:local/root local-root}
    {:git/url url :git/sha sha}))

(defn create-command
  "The argv that generates application `app-name` into `target-dir` from `p`
  (a `pin`). `local-root`, when given, replaces the pinned commit with a local
  clone of the template. deps-new reads `:name` as a symbol and `:target-dir` as
  an EDN string, hence the quoting."
  [p {:keys [app-name target-dir local-root]}]
  (let [deps {:deps {'io.github.seancorfield/deps-new (:deps-new p)
                     (:template p) (template-dep p local-root)}}]
    ["clojure" "-Srepro" "-Sdeps" (pr-str deps)
     "-X" "org.corfield.new/create"
     ":template" (str (:template p))
     ":name" (str app-name)
     ":target-dir" (pr-str (str target-dir))]))
