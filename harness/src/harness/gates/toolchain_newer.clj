(ns harness.gates.toolchain-newer
  "`bb toolchain-newer`: each pin of the KIT's toolchain beside its newest
  release - information for the person, never a fault, exit 0 always.

  The pins: the known-good set (`harness/resources/known-good.edn`, what a
  recorded health run ran together), the three versions only the `Dockerfile`
  pins (clojure-mcp-light's tag - known-good records its commit -,
  geckodriver, bbin), and the template's and deps-new's tags
  (`template-pins.edn`). The releases: each repository's tags by
  `git ls-remote`, no API and no token, the ones matching a release's shape -
  so a pre-release (`v2.56.0-rc1`) is never newest. A repository that does not
  answer is `unknown`, said as such.

  What moves is the person's, by the rule in the kit-session skill: at a
  branch's cut they read this and decide, a move being the plan's first item
  with its health runs; before a tag only an advisory moves a pin (register
  row 131). `bb kit-status` and `bb tag-check` print its newer lines. The
  KIT's own development only."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def sources
  "Each pin: where its releases are tagged, the shape of a release tag (its
  group the version), and where the pin is read."
  [{:tool :bb :repo "babashka/babashka" :tag #"v(\d+\.\d+\.\d+)" :pin [:known-good :bb]}
   {:tool :clj-kondo :repo "clj-kondo/clj-kondo" :tag #"v(\d+\.\d+\.\d+)" :pin [:known-good :clj-kondo]}
   {:tool :cljfmt :repo "weavejester/cljfmt" :tag #"(\d+\.\d+\.\d+)" :pin [:known-good :cljfmt]}
   {:tool :clojure :repo "clojure/brew-install" :tag #"(\d+\.\d+\.\d+\.\d+)" :pin [:known-good :clojure]}
   {:tool :git :repo "git/git" :tag #"v(\d+\.\d+\.\d+)" :pin [:known-good :git]}
   ;; the JDK's major is a constraint (toolchain.edn), so only 21's releases are asked
   {:tool :java :repo "adoptium/temurin21-binaries" :tag #"jdk-(21\.\d+\.\d+(?:\.\d+)?)\+\d+" :pin [:known-good :java]}
   {:tool :clojure-mcp-light :repo "bhauman/clojure-mcp-light" :tag #"v(\d+\.\d+\.\d+)" :pin [:dockerfile "MCP_LIGHT_TAG"]}
   {:tool :geckodriver :repo "mozilla/geckodriver" :tag #"v(\d+\.\d+\.\d+)" :pin [:dockerfile "GECKODRIVER_VERSION"]}
   {:tool :bbin :repo "babashka/bbin" :tag #"v(\d+\.\d+\.\d+)" :pin [:dockerfile "BBIN_VERSION"]}
   {:tool :template :repo "ontopro/clojure-stack-lite" :tag #"(kit-v\d+(?:\.\d+)*)" :pin [:template :git/tag]}
   {:tool :deps-new :repo "seancorfield/deps-new" :tag #"v(\d+\.\d+\.\d+)" :pin [:template :deps-new]}])

;; ---------------------------------------------------------------- pure

(defn version-key
  "`v` as a vector of its numbers, for comparing: `kit-v1.3` -> [1 3]."
  [v]
  (mapv parse-long (re-seq #"\d+" (str v))))

(defn compare-versions
  "Negative, zero or positive as `a` is older than, the same as or newer than
  `b`; a missing part counts as 0, so 1.3 = 1.3.0."
  [a b]
  (let [ka (version-key a) kb (version-key b)
        n (max (count ka) (count kb))
        pad #(into % (repeat (- n (count %)) 0))]
    (compare (pad ka) (pad kb))))

(defn newest
  "The newest release among `tags` whose whole name matches `pattern`, as its
  group captures it; nil when none does."
  [pattern tags]
  (->> tags
       (keep #(second (re-matches pattern %)))
       (sort compare-versions)
       last))

(defn dockerfile-args
  "The Dockerfile's `ARG NAME=default` lines, as {NAME default}."
  [text]
  (into {} (for [[_ k v] (re-seq #"(?m)^ARG ([A-Z_]+)=(\S+)" (or text ""))] [k v])))

(defn pins
  "Each source's pin from the three files' contents, as {tool version}; a pin a
  file does not carry is left out. The Dockerfile's `v` prefix is dropped, so a
  pin and a release are compared as the same shape."
  [{:keys [known-good dockerfile template-pins]}]
  (let [args (dockerfile-args dockerfile)
        template (get-in template-pins [:templates (:default template-pins)])]
    (into {}
          (keep (fn [{:keys [tool pin]}]
                  (when-let [v (case (first pin)
                                 :known-good (get-in known-good [:tools (second pin)])
                                 :dockerfile (some-> (get args (second pin)) (str/replace #"^v" ""))
                                 :template (case (second pin)
                                             :git/tag (:git/tag template)
                                             :deps-new (some-> (get-in template [:deps-new :git/tag]) (str/replace #"^v" ""))))]
                    [tool v])))
          sources)))

(defn rows
  "One row per pinned source: {:tool :pin :newest :status}, `:status` :newer
  when a release is newer than the pin, :ok when none is, :unknown when the
  repository did not answer. `releases` is {tool newest-or-nil}."
  [pins releases]
  (for [{:keys [tool]} sources
        :let [pin (get pins tool)]
        :when pin
        :let [nw (get releases tool)]]
    {:tool tool :pin pin :newest nw
     :status (cond (nil? nw) :unknown
                   (pos? (compare-versions nw pin)) :newer
                   :else :ok)}))

(defn line [{:keys [tool pin newest status]}]
  (case status
    :newer (str (name tool) " " pin " -> " newest " released")
    :unknown (str (name tool) " " pin " - newest unknown (its repository did not answer)")
    :ok (str (name tool) " " pin " - the newest")))

(defn newer-lines
  "The lines worth a reader's eye: a newer release, or a repository that did
  not answer. What `bb kit-status` and `bb tag-check` print."
  [rows]
  (map line (remove #(= :ok (:status %)) rows)))

;; ---------------------------------------------------------------- facts

(defn- tags
  "The tag names of a GitHub repository, by `git ls-remote`; nil when it does
  not answer within `timeout-ms`."
  [repo timeout-ms]
  (let [proc (p/process {:out :string :err :string :extra-env {"GIT_TERMINAL_PROMPT" "0"}}
                        "git" "ls-remote" "--tags" "--refs" (str "https://github.com/" repo ".git"))
        res (deref proc timeout-ms nil)]
    (if (and res (zero? (:exit res)))
      (map #(str/replace % #"^.*refs/tags/" "") (str/split-lines (:out res)))
      (do (when-not res (p/destroy-tree proc)) nil))))

(defn read-pins
  "The pins as the clone at `root` has them."
  [root]
  (let [read #(let [f (fs/path root %)] (when (fs/exists? f) (slurp (str f))))]
    (pins {:known-good (some-> (read "harness/resources/known-good.edn") edn/read-string)
           :dockerfile (read "Dockerfile")
           :template-pins (some-> (read "harness/resources/template-pins.edn") edn/read-string)})))

(defn check
  "The rows for the clone at `root`, every repository asked at once."
  ([root] (check root 20000))
  ([root timeout-ms]
   (let [ps (read-pins root)
         asked (into {} (for [{:keys [tool repo tag]} sources :when (get ps tool)]
                          [tool (future (some->> (tags repo timeout-ms) (newest tag)))]))]
     (rows ps (update-vals asked deref)))))

(defn -main [& _]
  (let [root (let [{:keys [exit out]} (p/shell {:out :string :err :string :continue true}
                                               "git" "rev-parse" "--show-toplevel")]
               (if (zero? exit) (str/trim out) "."))
        rs (check root)]
    (println "toolchain-newer: each pin beside its newest release (git ls-remote) - information, never a fault")
    (doseq [r rs] (println (str "  " (line r))))
    (println (str "\n" (count (filter #(= :newer (:status %)) rs)) " newer. A move is the person's: at a branch's cut, "
                  "the plan's first item with its health runs; before a tag, only for an advisory (kit-session skill)."))))
