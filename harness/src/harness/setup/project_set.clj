(ns harness.setup.project-set
  "The versions the KIT writes into a project (`resources/project-set.edn`): the application's
  `.mise.toml` java line, the Dockerfile's two JDK images and its tailwindcss, and `.gitignore`'s
  `.mise.local.toml` - over what the pinned template generated, never into the template itself.

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

(def java-note
  "The comment the KIT writes above the java line; its first line marks the line as the KIT's."
  (str "# THE JDK, WRITTEN BY THE KIT (`bb init`, `bb upgrade`): 25 is the version the KIT tests and\n"
       "# prefers; 21 and newer works. For 21 on this machine only, put `java = \"temurin-21\"` under\n"
       "# [tools] in .mise.local.toml - gitignored, and mise reads it over this file.\n"))

(defn with-java
  "`.mise.toml`'s java line set to `java`, the KIT's note above it, and the template's sentence
  that says to stay on 21 replaced."
  [text java]
  (let [line-re #"(?m)^[ \t]*java[ \t]*=[ \t]*\"[^\"]*\"[ \t]*$"]
    (if-not (re-find line-re text)
      {:text text :missed ["the java line under [tools]"]}
      (let [line (str "java = \"" java "\"")
            first-note (first (str/split-lines java-note))
            t (if (str/includes? text first-note)
                (str/replace text line-re line)
                (str/replace text line-re (str/re-quote-replacement (str java-note line))))]
        {:text (str/replace t #"(?s)The one real constraint is the JDK: stay on 21 \(.*?\)\."
                            "The JDK is 21 and newer, 25 the default (the java line below).")
         :missed []}))))

(defn with-images
  "The Dockerfile's build stage on `build-image` (a `clojure:temurin-N-tools-deps-...` FROM, its
  `AS` name kept), its runtime stage on `runtime-image` (an `eclipse-temurin:` FROM), and its
  `TAILWIND_VERSION` argument at `tailwindcss`."
  [text {:keys [build-image runtime-image tailwindcss]}]
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
           ["ARG TAILWIND_VERSION"
            #"(?m)^ARG[ \t]+TAILWIND_VERSION=\S*"
            (str "ARG TAILWIND_VERSION=v" tailwindcss)]]))

(defn with-ignore
  "`.gitignore` with `.mise.local.toml` in it."
  [text]
  {:text (if (some #{".mise.local.toml" "/.mise.local.toml"} (map str/trim (str/split-lines text)))
           text
           (str text (when-not (or (str/blank? text) (str/ends-with? text "\n")) "\n") ".mise.local.toml\n"))
   :missed []})

(def files
  "Each file the KIT writes, and how."
  [[".mise.toml" (fn [t s] (with-java t (:java s)))]
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
                 (update acc :missed conj (str rel ": the file"))
                 (let [old (slurp (str p))
                       {:keys [text missed]} (f old s)]
                   (when (not= old text) (spit (str p) text))
                   (cond-> (update acc :missed into (map #(str rel ": " %) missed))
                     (not= old text) (update :changed conj rel))))))
           {:changed [] :missed []}
           files)))
