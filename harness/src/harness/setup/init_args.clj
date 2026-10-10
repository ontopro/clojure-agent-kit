(ns harness.setup.init-args
  "`bb init`'s arguments, parsed - here and not in `harness.setup.init`, because
  the KIT's root `bb.edn` parses them too and has none of the harness's
  dependencies on its classpath. Requires nothing.

  The root's own parse once knew only `--seat` as a flag with a value, and read
  a later flag's value as the workspace folder: one parse, used by both, is the fix.")

(def ^:private valued-flags
  "The flags that take the next argument as their value, each to its key."
  {"--seat" :seat "--app" :app "--build" :build})

(defn parse-args
  "`bb init`'s arguments as a map: `:positional` (name, dir), `:seat`, `:app`
  and `:build` (the value after each flag), `:dry-run?`. The valued flags are
  parsed here and not by looking for a leading `--`."
  [args]
  (loop [[a & more] args, m {:positional []}]
    (cond
      (nil? a) m
      (valued-flags a) (recur (rest more) (assoc m (valued-flags a) (first more)))
      (= a "--dry-run") (recur more (assoc m :dry-run? true))
      :else (recur more (update m :positional conj a)))))

(defn root-args
  "The arguments the KIT's root `bb.edn` hands to `bb init` in `harness/`: parsed
  by `parse-args`, so a valued flag travels with its value, and `dir` made
  absolute by `absolute`, since what was typed at
  the root would otherwise be read from inside `harness/`."
  [args absolute]
  (let [{:keys [positional seat app build dry-run?]} (parse-args args)
        [project dir & more] positional]
    (vec (concat (when project [project])
                 (when dir [(absolute dir)])
                 more
                 (when seat ["--seat" seat])
                 (when app ["--app" app])
                 (when build ["--build" build])
                 (when dry-run? ["--dry-run"])))))
