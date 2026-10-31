(ns harness.gates.toolchain-image
  "The name of the KIT's toolchain image - a fresh Linux machine in Docker with only the KIT's
  toolchain - made in one place: the root `bb.edn`'s `docker-image!` builds it under this name, the
  security review's sandbox and the review REPL run it by this name, and `bb tag-check` prints it in
  the Docker Scout command a person runs before a tag.

  THE NAME SAYS WHAT THE IMAGE HOLDS: known-good's date and the first 7 hex of a sha256 over what the
  image is built from - the `Dockerfile` and every file of the toolchain pack, hidden ones too, exactly
  the build context `.dockerignore` lets in, so the download matrix's versions and the pack's code
  together. A change not yet built is \"Unable to find image\", never the old toolchain run by a name it
  no longer holds (From Fable 5.1's reads of `720f2af` and `1de97ad`)."
  (:require
   [babashka.fs :as fs]
   [harness.setup.known-good :as known-good]))

(defn image-context
  "What the KIT's toolchain image is built from in the clone `kit`, as `[relative-path content]`
  sorted: the `Dockerfile` and every file of the toolchain pack (`tools/toolchain/`), hidden files
  included, as `.dockerignore`'s `!tools/toolchain` sends them."
  [kit]
  (sort-by first
           (for [f (cons (fs/path kit "Dockerfile")
                         (filter fs/regular-file? (fs/glob (fs/path kit "tools" "toolchain") "**" {:hidden true})))]
             [(str (fs/relativize kit f)) (slurp (str f))])))

(defn context-digest
  "The first 7 hex of the sha256 over `image-context`'s paths and contents - pure."
  [context]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (doseq [[path content] context]
      (.update md (.getBytes (str path "\u0000" content "\u0000") "UTF-8")))
    (subs (format "%064x" (BigInteger. 1 (.digest md))) 0 7)))

(defn image-tag
  "The KIT's toolchain image's name, as built from the clone `kit`."
  [kit]
  (str "clojure-agent-kit-toolchain:"
       (:as-of (known-good/read-set (fs/path kit "harness")))
       "-" (context-digest (image-context kit))))
