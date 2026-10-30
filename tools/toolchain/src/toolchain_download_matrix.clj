(ns toolchain-download-matrix
  "THE DOWNLOAD MATRIX, read: `download-matrix.edn` beside the pack's `bb.edn` names each tool the
  pack fetches - its exact version, where each platform's archive is and the sha256 it must have.
  Pure functions over that map: the platform this machine is, a tool's archive for a platform, the
  tools a machine takes (an `:image-only` one only when named), where a tool lives in the cache,
  and a publisher's checksum read out of the file it publishes. `toolchain_status.clj` and the
  `toolchain-sum` task do the looking and the fetching.

  A platform is one of `platforms`; any other is refused by name, never guessed. An Intel Mac is not
  one (the person, 2026-10-30): cljfmt publishes no build for it, and the KIT runs on Apple silicon
  and on Linux."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def platforms [:macos-aarch64 :linux-aarch64 :linux-amd64])

(defn commit-version
  "`row` with its `:version` the first seven characters of its `:git/sha` when it has a sha and no
  version - pure; the harness's `kit-pins` reads the matrix the same way."
  [{:keys [version] sha :git/sha :as row}]
  (if (and sha (nil? version)) (assoc row :version (subs sha 0 7)) row))

(defn read-matrix
  "The matrix in `f`, its tools in the file's order - the install's order: bbin before what it installs.
  EDN reads more than eight keys as a hash map, so the order is taken from the text, each top-level key
  at the start of its line (` :bbin {`) - and the keys found must be the map's, or the file is refused.
  A bbin row's `:version` is its `:git/sha`'s first seven characters, as known-good writes a commit -
  one field in the file, not two held equal (From Fable 5.1's read of `3dbdcda`)."
  [f]
  (let [text (slurp f)
        m (edn/read-string text)
        ks (map (comp keyword second) (re-seq #"(?m)^[ {]:([a-z][a-z0-9-]*) \{" text))]
    (when-not (and (= (count ks) (count m)) (= (set ks) (set (keys m))))
      (throw (ex-info (str f ": each tool's key must open its line, as ` :bb {` does - the order is read from them")
                      {:found ks :keys (keys m)})))
    (apply array-map (mapcat (fn [k] [k (commit-version (get m k))]) ks))))

(defn bbin? "Whether `tool` is installed by bbin from a repository at a commit (`:unpack :bbin`)."
  [matrix tool]
  (= :bbin (get-in matrix [tool :unpack])))

(defn bbin-commit
  "The commit a bbin wrapper's header names - its text between `; :bbin/start` and `; :bbin/end`, the
  `:git/sha` there - or nil when the text has no such header."
  [text]
  (some->> (re-find #"(?s); :bbin/start(.*?); :bbin/end" (or text ""))
           second
           (re-find #":git/sha \"([0-9a-f]{40})\"")
           second))

(defn platform-key
  "This machine's platform from the JVM's `os.name` and `os.arch` (bb's own), as the matrix keys it."
  [os-name os-arch]
  (let [os (cond (str/starts-with? os-name "Mac") "macos"
                 (str/starts-with? os-name "Linux") "linux")
        arch (case os-arch
               ("aarch64" "arm64") "aarch64"
               ("amd64" "x86_64") "amd64"
               nil)]
    (if (and os arch (some #{(keyword (str os "-" arch))} platforms))
      (keyword (str os "-" arch))
      (throw (ex-info (str "no toolchain for " os-name " " os-arch
                           " - the download matrix has " (str/join ", " (map name platforms)))
                      {:os-name os-name :os-arch os-arch})))))

(defn os-of [platform] (keyword (first (str/split (name platform) #"-"))))

(defn- fill
  "A URL or a path with its placeholders filled: `{version}`, `{asset}`, and the two spellings
  Temurin's file names use, `{version-url}` (`+` as `%2B`) and `{version-file}` (`+` as `_`)."
  [template version asset]
  (-> template
      (str/replace "{version-url}" (str/replace version "+" "%2B"))
      (str/replace "{version-file}" (str/replace version "+" "_"))
      (str/replace "{version}" version)
      (str/replace "{asset}" (or asset ""))))

(defn asset
  "Tool `tool`'s archive for `platform`: `{:tool :version :platform :url :file :sha256 :checksum-url}`,
  `:checksum-url` nil where the publisher publishes none. A tool with one archive for every
  platform (no `:platforms`) gives that one - on every platform of its `:os` when it names one, a
  source tarball that builds on one OS. A platform the tool has no archive for is nil."
  [matrix tool platform]
  (let [{:keys [version url checksum-url platforms sha256 os] :as row} (get matrix tool)
        p (if platforms (get platforms platform) {:sha256 sha256})]
    (cond
      ;; no archive: the repository and its commit, on every platform
      (bbin? matrix tool) {:tool tool :version version :platform platform
                           :git/url (:git/url row) :git/sha (:git/sha row)}
      (and version p (or (nil? os) (nil? platform) (= os (os-of platform))))
      (let [u (fill url version (:asset p))]
        {:tool tool :version version :platform platform
         :url u :file (subs u (inc (str/last-index-of u "/")))
         :sha256 (:sha256 p)
         :checksum-url (some-> checksum-url (fill version (:asset p)))}))))

(defn assets
  "Every archive of `tool`: one per platform it has, or its one archive."
  [matrix tool]
  (if (:platforms (get matrix tool))
    (keep #(asset matrix tool %) platforms)
    [(asset matrix tool nil)]))

(defn machine-tools
  "The tools a machine on `platform` takes, in the matrix's order: every tool with an archive for
  it, an `:image-only` one only when `only` names it. `only` nil is every tool not image-only; a
  tool `only` names that the matrix has not is refused by name."
  [matrix platform only]
  (when-let [unknown (seq (remove matrix only))]
    (throw (ex-info (str "not in the download matrix: " (str/join ", " (map name unknown))
                         " - it has " (str/join ", " (map name (keys matrix))))
                    {:unknown unknown})))
  (->> (keys matrix)
       (filter #(asset matrix % platform))
       (filter #(if only
                  (contains? (set only) %)
                  (not (:image-only (get matrix %)))))))

(defn bin-path
  "Where `tool`'s executable is in the cache `cache` on `platform`: `<cache>/<tool>/<version>/` and
  the matrix's `:bin` under it - for the JDK, under its `:home` for the platform's OS; a bbin row's
  first command, which bbin writes into `<cache>/bin/` itself."
  [matrix cache tool platform]
  (let [{:keys [version bin home]} (get matrix tool)
        dir (str cache "/" (name tool) "/" version)]
    (cond (bbin? matrix tool) (str cache "/bin/" bin)
          home (str dir "/" (fill (get home (os-of platform)) version nil) "/" bin)
          :else (str dir "/" bin))))

(defn in-cache?
  "Whether `tool` is in the cache: its executable there (`executable?`) - for a bbin row, each of its
  commands in `bin-dir` with a header naming the row's commit (`commit-of`, a path to its commit or nil)."
  [matrix tool bin bin-dir executable? commit-of]
  (if (bbin? matrix tool)
    (every? #(= (get-in matrix [tool :git/sha]) (commit-of (str bin-dir "/" (:name %))))
            (get-in matrix [tool :commands]))
    (boolean (executable? bin))))

(defn published-sha256
  "The sha256 a publisher's checksum file gives for `file`: the line whose words name the file
  exactly (`sha256sum`'s `*` before it allowed), else, in a file of one line, that line - a
  checksum file for one archive may name none. Its 64 hex characters, lower case; nil when no line
  answers."
  [text file]
  (let [lines (remove str/blank? (str/split-lines (or text "")))
        names? (fn [l] (some #{file (str "*" file)} (str/split (str/trim l) #"\s+")))
        line (or (first (filter names? lines))
                 (when (= 1 (count lines)) (first lines)))]
    (some->> line (re-find #"(?i)\b[0-9a-f]{64}\b") str/lower-case)))

(defn compare-versions
  "Negative, zero or positive as version `a` is older than, the same as or newer than `b`, by their
  numbers - a build after `+` is not the version, a missing part is 0."
  [a b]
  (let [k #(mapv parse-long (re-seq #"\d+" (first (str/split (str %) #"\+"))))
        ka (k a) kb (k b)
        n (max (count ka) (count kb))
        pad #(into % (repeat (- n (count %)) 0))]
    (compare (pad ka) (pad kb))))

(defn plan
  "The install's steps on `platform` into the cache `cache`, one per tool `machine-tools` gives for
  `only`, in the matrix's order - pure:
    `:kept`    the tool's executable is at its cache path (`present?`);
    `:skip`    bb, when the bb running the install (`running`'s `:bb`) is at or above the matrix's -
               the install cannot replace the bb that runs it, and a newer one is used as it is (the
               person, 2026-10-29); the JDK when `only` does not name it - 200 MB, fetched only when
               asked;
    `:install` the archive, the folder it lands in, the executable, and where the archive is kept
               (`<cache>/downloads/`, reused while its sha256 is the matrix's) - for a bbin row (`:kind
               :bbin`), its repository, commit and commands, no archive. A bbin row is `:kept` when each
               command in `bin-dir` - where bbin writes them, `<cache>/bin` unless `--link` names
               another - names its commit (`in-cache?`).
  Each step `{:tool :version :step …}`."
  [matrix cache platform {:keys [only running present? commit-of bin-dir]
                          :or {present? (constantly false) commit-of (constantly nil)}}]
  (for [t (machine-tools matrix platform only)
        :let [v (:version (get matrix t))
              bin-dir (or bin-dir (str cache "/bin"))
              bin (if (bbin? matrix t) (str bin-dir "/" (get-in matrix [t :bin])) (bin-path matrix cache t platform))
              base {:tool t :version v :bin bin}]]
    (cond (in-cache? matrix t bin bin-dir present? commit-of) (assoc base :step :kept)
          (and (= :bb t) (get running :bb) (not (neg? (compare-versions (get running :bb) v))))
          (assoc base :step :skip :why (str "the bb running this is " (get running :bb) ", at or above " v))
          (and (= :java t) (not (some #{:java} only)))
          (assoc base :step :skip :why "fetched only when named: --only java")
          (bbin? matrix t)
          (assoc base :step :install :kind :bbin
                 :git/url (get-in matrix [t :git/url]) :git/sha (get-in matrix [t :git/sha])
                 :commands (get-in matrix [t :commands]))
          :else
          (let [a (asset matrix t platform)]
            (assoc base :step :install :asset a :dir (str cache "/" (name t) "/" v)
                   :download (str cache "/downloads/" (:file a)))))))

(def image-tools
  "What the KIT's toolchain image fetches by shell, before bb exists, and so takes as build
  arguments; every other tool it installs by running the pack (plan 7.0, item 8.1c, D-9)."
  [[:bb "BB"] [:git "GIT"]])

(defn image-args
  "The KIT's toolchain image's build arguments for the Linux `platform` it is built for:
  `<P>_VERSION`, `<P>_URL` and `<P>_SHA256` of each of `image-tools` - pure. A platform none of
  them has an archive for is refused by name, never a map of nils."
  [matrix platform]
  (when-not (some #{platform} (filter #(= :linux (os-of %)) platforms))
    (throw (ex-info (str "no KIT toolchain image for " (if platform (name platform) "no platform")
                         " - it is built for " (str/join ", " (map name (filter #(= :linux (os-of %)) platforms))))
                    {:platform platform})))
  (into (sorted-map)
        (mapcat (fn [[tool prefix]]
                  (let [{:keys [version url sha256]} (asset matrix tool platform)]
                    [[(str prefix "_VERSION") version] [(str prefix "_URL") url] [(str prefix "_SHA256") sha256]])))
        image-tools))
