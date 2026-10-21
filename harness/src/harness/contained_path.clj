(ns harness.contained-path
  "Whether a path someone else named stays inside a folder - the one check every tool and reader
  that takes a path from a model's answer, a spec, a blueprint or a review record makes before it
  reads or writes. One function, so the places cannot drift apart again: seven of them had each
  checked in its own way, or not at all.

  A PATH IS COMPARED BY ITS PARTS, NEVER AS A STRING. `/w/run-1-coder.nrepl.log` starts with the
  string `/w/run-1-coder` and is not inside it; `fs/starts-with?` compares whole components.

  A SYMLINK IS FOLLOWED BEFORE IT IS TRUSTED. A link inside the folder can point anywhere, and a
  project's repository is what a model may have written: when the path, or the nearest part of it
  that exists, resolves outside the folder's own real path, it is outside.

  AN ABSOLUTE PATH IS REFUSED. What a model, a spec or a record names is relative to the folder it
  is read against; an absolute one is never a file of that folder."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(defn- real
  "`p`'s real path, or - for a path that does not exist yet - its nearest existing ancestor's real
  path with the rest appended."
  [p]
  (loop [q p rest-parts ()]
    (cond (nil? q) p
          (fs/exists? q) (reduce fs/path (fs/real-path q) rest-parts)
          :else (recur (fs/parent q) (cons (str (fs/file-name q)) rest-parts)))))

(defn inside
  "`path` (a string or a path, relative to `root`) resolved against `root`, when it stays inside it:
  the normalized absolute path. Nil when it is blank, absolute, leaves `root` once `..` is resolved,
  or resolves outside `root` through a symlink. `root` itself counts as inside."
  [root path]
  (when-not (or (nil? path) (str/blank? (str path)) (fs/absolute? (fs/path (str path))))
    (let [base (fs/normalize (fs/absolutize root))
          p (fs/normalize (fs/absolutize (fs/path base (str path))))]
      (when (and (fs/starts-with? p base)
                 (fs/starts-with? (real p) (real base)))
        p))))
