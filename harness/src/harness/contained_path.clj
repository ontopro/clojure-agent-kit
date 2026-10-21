(ns harness.contained-path
  "Whether a path someone else named stays inside a folder - the one check every tool and reader
  that takes a path from a model's answer, a spec, a blueprint or a review record makes before it
  reads or writes. One function, so the places cannot drift apart again: seven of them had each
  checked in its own way, or not at all.

  A PATH IS COMPARED BY ITS PARTS, NEVER AS A STRING. `/w/run-1-coder.nrepl.log` starts with the
  string `/w/run-1-coder` and is not inside it; `fs/starts-with?` compares whole components.

  A SYMLINK IS FOLLOWED BEFORE IT IS TRUSTED. A link inside the folder can point anywhere, and a
  project's repository is what a model may have written: every link in the path is followed, part by
  part - a dangling one to where it points, since a write through it lands there - and when the path
  lands outside where the folder itself lands, it is outside.

  AN ABSOLUTE PATH IS REFUSED. What a model, a spec or a record names is relative to the folder it
  is read against; an absolute one is never a file of that folder."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(def ^:private max-links
  "Links followed in one path before it is taken for a loop, and refused."
  40)

(defn- real
  "Where absolute path `p` lands, every link in it followed - a link that dangles too: its target is
  where a write through it lands. Walked part by part, never asking whether a part exists through a
  link (`fs/exists?` follows one, and a dangling link had read as a file not yet written, its nearest
  existing ancestor inside, and the write created the target outside). Nil for a loop of links."
  [p]
  (loop [done (.getRoot (fs/path p)) parts (map str (fs/components p)) hops 0]
    (cond
      (> hops max-links) nil
      (empty? parts) (fs/normalize done)
      :else
      (let [nxt (fs/path done (first parts))]
        (if (fs/sym-link? nxt)
          (let [target (fs/path (str (fs/read-link nxt)))
                target (fs/normalize (if (fs/absolute? target) target (fs/path done target)))]
            (recur (.getRoot target) (concat (map str (fs/components target)) (rest parts)) (inc hops)))
          (recur (fs/normalize nxt) (rest parts) hops))))))

(defn inside
  "`path` (a string or a path, relative to `root`) resolved against `root`, when it stays inside it:
  the normalized absolute path. Nil when it is blank, absolute, leaves `root` once `..` is resolved,
  or resolves outside `root` through a symlink. `root` itself counts as inside."
  [root path]
  (when-not (or (nil? path) (str/blank? (str path)) (fs/absolute? (fs/path (str path))))
    (let [base (fs/normalize (fs/absolutize root))
          p (fs/normalize (fs/absolutize (fs/path base (str path))))
          rp (real p)
          rb (real base)]
      (when (and (fs/starts-with? p base) rp rb (fs/starts-with? rp rb))
        p))))
