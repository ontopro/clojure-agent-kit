(ns harness.setup.project-set
  "The project's toolchain, which the KIT owns (`resources/project-set.edn`): every line under the
  application's `.mise.toml` [tools], the Dockerfile's two images and its bb and tailwindcss
  versions, and `.gitignore`'s `.mise.local.toml` - written over what the pinned template generated,
  never into the template itself.

  PURE BUT FOR `write!`. Each `with-*` takes a file's text and returns `{:text :missed}`: the text
  rewritten, and what it looked for and did not find - a project that changed its Dockerfile's
  FROM keeps its own, said, never guessed at. Idempotent: written twice is written once."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def resource-name "project-set.edn")

(defn load-set
  "The set, from the classpath or from any slurpable thing (tests)."
  ([] (load-set (or (io/resource resource-name)
                    (throw (ex-info "project-set.edn not on the classpath" {:resource resource-name})))))
  ([source] (edn/read-string (slurp source))))

(def tools-note
  "The comment the KIT writes above `[tools]`; its first line marks the table as the KIT's."
  (str "# THE VERSIONS UNDER [tools] ARE THE KIT'S: `bb init` and `bb upgrade` write them from its\n"
       "# tested set, over the template's. The JDK: 25 is the one the KIT tests and prefers; 21 and\n"
       "# newer works. For 21 on this machine only, put `java = \"temurin-21\"` under [tools] in\n"
       "# .mise.local.toml - gitignored, and mise reads it over this file.\n"))

(def ^:private template-sentence #"(?s)The one real constraint is the JDK: stay on 21 \(.*?\)\.")

(def kit-sentence
  "What the KIT writes in place of the template's sentence that says to stay on 21: two comment
  lines, so the paragraph keeps its width."
  "The JDK is 21 and newer, 25 the default\n# (the java line below).")

(defn- tool-line-re [tool]
  (re-pattern (str "(?m)^[ \\t]*" (java.util.regex.Pattern/quote (name tool)) "[ \\t]*=[ \\t]*\"[^\"]*\"[ \\t]*$")))

(defn- tools-table
  "The [tools] table's lines - from its header to the next table or the end - as `[start end]`
  offsets into `text`, or nil when there is none. Only these lines are the tools': the [alias]
  table below names the same tools."
  [text]
  (let [m (re-matcher #"(?ms)^\[tools\][ \t]*\n(.*?)(?=^\[|\z)" text)]
    (when (.find m) [(.start m 1) (.end m 1)])))

(defn with-tools
  "`.mise.toml` with every line under [tools] that `tools` names set to its value, the KIT's note
  above the table, and the template's sentence that says to stay on 21 replaced. A tool with no
  line is said, never added; so is the sentence when neither it nor the KIT's is there."
  [text tools]
  (if-let [[from to] (tools-table text)]
    (let [[body missed] (reduce (fn [[body missed] [tool v]]
                                  (let [re (tool-line-re tool)]
                                    (if (re-find re body)
                                      [(str/replace body re (str/re-quote-replacement (str (name tool) " = \"" v "\""))) missed]
                                      [body (conj missed (str "the " (name tool) " line under [tools]"))])))
                                [(subs text from to) []]
                                (sort-by key tools))
          t (str (subs text 0 from) body (subs text to))
          t (if (str/includes? t (first (str/split-lines tools-note)))
              t
              (str/replace-first t #"(?m)^\[tools\][ \t]*$" (str/re-quote-replacement (str tools-note "[tools]"))))
          [t missed] (cond (re-find template-sentence t) [(str/replace t template-sentence (str/re-quote-replacement kit-sentence)) missed]
                           (str/includes? t kit-sentence) [t missed]
                           :else [t (conj missed "the template's sentence on the JDK")])]
      {:text t :missed missed})
    {:text text :missed ["the [tools] table"]}))

(defn with-images
  "The Dockerfile's build stage on `build-image` (a `clojure:temurin-N-tools-deps-...` FROM, its
  `AS` name kept), its runtime stage on `runtime-image` (an `eclipse-temurin:` FROM), and its
  `BB_VERSION` and `TAILWIND_VERSION` arguments at the set's babashka and tailwindcss."
  [text {:keys [build-image runtime-image tools]}]
  (reduce (fn [{:keys [text missed]} [what re rep]]
            (if (re-find re text)
              {:text (str/replace text re rep) :missed missed}
              {:text text :missed (conj missed what)}))
          {:text text :missed []}
          [["the build stage's FROM clojure:temurin-..."
            #"(?m)^FROM[ \t]+clojure:temurin-\d+-tools-deps-\S+"
            (str "FROM " build-image)]
           ["the runtime stage's FROM eclipse-temurin:..."
            #"(?m)^FROM[ \t]+eclipse-temurin:\S+"
            (str "FROM " runtime-image)]
           ["ARG BB_VERSION"
            #"(?m)^ARG[ \t]+BB_VERSION=\S*"
            (str "ARG BB_VERSION=" (:babashka tools))]
           ["ARG TAILWIND_VERSION"
            #"(?m)^ARG[ \t]+TAILWIND_VERSION=\S*"
            (str "ARG TAILWIND_VERSION=v" (:tailwindcss tools))]]))

(defn with-ignore
  "`.gitignore` with `.mise.local.toml` in it."
  [text]
  {:text (if (some #{".mise.local.toml" "/.mise.local.toml"} (map str/trim (str/split-lines text)))
           text
           (str text (when-not (or (str/blank? text) (str/ends-with? text "\n")) "\n") ".mise.local.toml\n"))
   :missed []})

(def files
  "Each file the KIT writes, and how."
  [[".mise.toml" (fn [t s] (with-tools t (:tools s)))]
   ["Dockerfile" with-images]
   [".gitignore" (fn [t _] (with-ignore t))]])

(defn write!
  "The set written into the application at `app-dir`: `{:changed [relative paths] :missed [what was
  not found, with its file]}`. A file the application does not have is missed, and said. Commits
  nothing; the caller does."
  ([app-dir] (write! app-dir (load-set)))
  ([app-dir s]
   (reduce (fn [acc [rel f]]
             (let [p (fs/path app-dir rel)]
               (if-not (fs/exists? p)
                 (update acc :missed conj (str rel ": none in the application"))
                 (let [old (slurp (str p))
                       {:keys [text missed]} (f old s)]
                   (when (not= old text) (spit (str p) text))
                   (cond-> (update acc :missed into (map #(str rel ": " %) missed))
                     (not= old text) (update :changed conj rel))))))
           {:changed [] :missed []}
           files)))
