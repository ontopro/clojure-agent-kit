(ns toolchain-download-matrix
  "THE DOWNLOAD MATRIX, read: `download-matrix.edn` beside the pack's `bb.edn` names each tool the
  pack fetches - its exact version, where each platform's archive is and the sha256 it must have.
  Pure functions over that map: the platform this machine is, a tool's archive for a platform, the
  tools a machine takes (an `:image-only` one only when named), where a tool lives in the cache,
  and a publisher's checksum read out of the file it publishes. `toolchain_status.clj` and the
  `toolchain-sum` task do the looking and the fetching.

  A platform is one of `platforms`; a fifth is refused by name, never guessed."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def platforms [:macos-aarch64 :macos-amd64 :linux-aarch64 :linux-amd64])

(defn read-matrix [f] (edn/read-string (slurp f)))

(defn platform-key
  "This machine's platform from the JVM's `os.name` and `os.arch` (bb's own), as the matrix keys it."
  [os-name os-arch]
  (let [os (cond (str/starts-with? os-name "Mac") "macos"
                 (str/starts-with? os-name "Linux") "linux")
        arch (case os-arch
               ("aarch64" "arm64") "aarch64"
               ("amd64" "x86_64") "amd64"
               nil)]
    (if (and os arch)
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
  (let [{:keys [version url checksum-url platforms sha256 os]} (get matrix tool)
        p (if platforms (get platforms platform) {:sha256 sha256})]
    (when (and version p (or (nil? os) (nil? platform) (= os (os-of platform))))
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
  the matrix's `:bin` under it - for the JDK, under its `:home` for the platform's OS."
  [matrix cache tool platform]
  (let [{:keys [version bin home]} (get matrix tool)
        dir (str cache "/" (name tool) "/" version)]
    (if home
      (str dir "/" (fill (get home (os-of platform)) version nil) "/" bin)
      (str dir "/" bin))))

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
