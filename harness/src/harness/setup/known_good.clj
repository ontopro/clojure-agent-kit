(ns harness.setup.known-good
  "The KIT's known-good set - the versions run together through its gates, `bb init`, the loop and the
  generated application's gates - read from the Mac's health record, `health/records/macos-arm64.edn`.
  ONE FILE FOR ONE FACT: `resources/known-good.edn` was a copy `bb health --record` wrote beside the
  record, from the same run (plan 7.0, item 8.3f). A leaf, read by the doctor's column and header,
  `bb tag-check`'s versions and its stale records, and the toolchain image's name - the last two from
  the root `bb.edn` too, where no harness resource is on the classpath, so every reader passes the
  KIT's `harness` folder."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]))

(def platform
  "The platform whose health record is the known-good set: the machine that certifies the KIT, and
  the only record with `:project`. Both platforms install from the same download matrix, so their
  tools are the same; only the date and the label would differ."
  "macos-arm64")

(defn record-path
  "The path of the health record that is the known-good set, under the KIT's `harness` folder."
  [harness]
  (str (fs/path harness "health" "records" (str platform ".edn"))))

(defn read-set
  "The known-good set from the record under `harness`: `{:as-of _ :platform <label> :tools {tool
  version}}`, with `:xtdb` and `:project` when the record has them; nil with no record."
  [harness]
  (let [f (record-path harness)]
    (when (fs/exists? f)
      (let [r (edn/read-string (slurp f))]
        (cond-> {:as-of (:health/as-of r) :platform (get-in r [:platform :label]) :tools (:tools r)}
          (:xtdb r) (assoc :xtdb (:xtdb r))
          (:project r) (assoc :project (:project r)))))))
