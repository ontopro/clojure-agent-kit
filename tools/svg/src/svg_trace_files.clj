(ns svg-trace-files
  "Where the trace command reads and writes, held to the folders it was given. An icons file is a
  project's, and a project's repository is what a model may have written: an icon's name became a
  file name under the output folder and a cell of the contact sheet, and a picture's path was read
  from wherever it pointed - a name with `../` wrote outside the folder, a link planted there wrote
  through it, a picture path copied a host file into the overlay, and a name was markup in the sheet.

  AN ICON'S NAME IS A NAME: `[A-Za-z0-9_-]+`, refused by name before anything is written. A PICTURE
  IS A FILE OF THE ICONS FILE'S FOLDER, links followed before it is trusted. A WRITE NEVER FOLLOWS A
  LINK. Plain `java.nio`, so the pack runs on the Clojure CLI with no library and its tests in bb."
  (:require [clojure.string :as str])
  (:import [java.io File]
           [java.nio ByteBuffer]
           [java.nio.file Files LinkOption Path StandardOpenOption]
           [java.nio.file.attribute FileAttribute]))

(def name-re
  "An icon's name: a file name and a cell of the contact sheet, so letters, digits, `_` and `-`."
  #"[A-Za-z0-9_-]+")

(defn bad-names
  "The names in `names` that are not an icon's name (`name-re`), in order."
  [names]
  (vec (remove #(and (string? %) (re-matches name-re %)) names)))

;; no option given: `isRegularFile` and `toRealPath` FOLLOW every link, so the file judged is the one
;; the path lands on. Never `NOFOLLOW_LINKS` here: `toRealPath` then resolves no link at all, and a
;; picture through a linked folder would be judged where it is written, not where it lands
(def ^:private follow-links (make-array LinkOption 0))

(defn picture-file
  "The picture `png` names, relative to `base` (the icons file's folder), as the file it lands on
  when that is a regular file inside `base` with every link followed; nil otherwise - blank, not a
  string, absolute, out by `..` or out through a link. The file returned is the one judged."
  ^File [^File base png]
  (when (and (string? png) (not (str/blank? png)) (not (.isAbsolute (.toPath (File. ^String png)))))
    (let [b (.normalize (.toPath (.getAbsoluteFile base)))
          p (.normalize (.resolve b ^String png))]
      (when (and (.startsWith p b) (Files/isRegularFile p follow-links))
        (let [rp (.toRealPath p follow-links)]
          (when (.startsWith rp (.toRealPath b follow-links)) (.toFile rp)))))))

(defn out-folder
  "The output folder: `out` as the person gave it, else `traced/` beside the icons file - refused
  when that is a link, since the project's repository made it."
  ^File [^File base out]
  (if out
    (File. ^String out)
    (let [f (File. base "traced")]
      (when (Files/isSymbolicLink (.toPath f))
        (throw (ex-info (str f " is a link - not written through; remove it, or give --out") {:out (str f)})))
      f)))

(defn write!
  "Write `text` to `file-name` in the folder `out`, never through a link: a link there is refused by
  name, and the file is opened with no link followed, so one planted between the two is refused too
  and what it points at is untouched. The path written."
  ^Path [^File out ^String file-name ^String text]
  (let [p (.toPath (File. out file-name))]
    (when (Files/isSymbolicLink p)
      (throw (ex-info (str p " is a link - not written through") {:file (str p)})))
    (with-open [c (Files/newByteChannel p (java.util.HashSet. [StandardOpenOption/CREATE StandardOpenOption/TRUNCATE_EXISTING
                                                               StandardOpenOption/WRITE LinkOption/NOFOLLOW_LINKS])
                                        (make-array FileAttribute 0))]
      (.write c (ByteBuffer/wrap (.getBytes text "UTF-8"))))
    p))

(defn escape-html
  "`s` as text in HTML: `&`, `<`, `>` and both quotes as entities."
  [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))
