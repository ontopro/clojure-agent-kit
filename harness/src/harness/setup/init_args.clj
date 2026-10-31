(ns harness.setup.init-args
  "`bb init`'s arguments, parsed. Requires nothing: the KIT's root `bb.edn` once parsed
  them too, to make the workspace folder absolute, and its classpath has none of the
  harness's dependencies; the folder argument went in plan 7.0, item 8.3d, and the root
  now hands the arguments on as typed.")

(def ^:private valued-flags
  "The flags that take the next argument as their value, each to its key."
  {"--seat" :seat "--app" :app "--build" :build})

(defn parse-args
  "`bb init`'s arguments as a map: `:positional` (the name; a second is refused), `:seat`, `:app`
  and `:build` (the value after each flag), `:dry-run?`. The valued flags are
  parsed here and not by looking for a leading `--`."
  [args]
  (loop [[a & more] args, m {:positional []}]
    (cond
      (nil? a) m
      (valued-flags a) (recur (rest more) (assoc m (valued-flags a) (first more)))
      (= a "--dry-run") (recur more (assoc m :dry-run? true))
      :else (recur more (update m :positional conj a)))))

