;; seed — see PROVENANCE.md; this is a fork of thub-harness, not a mirror.
;; Not extracted: written for the seed.
(ns harness.profile
  "Which model answers for which role, and the one client the human works in.

  WHY THIS EXISTS. Three separate things were being conflated every time this
  came up in conversation: the interactive SEAT (exactly one per project), the
  dispatch HARNESS (this code), and the model FAMILY per dispatched role. The
  seat is where a human sits; the roles are reached over HTTP and are nobody's
  editor. Writing them down in one shape is what stops the question recurring.

  AND IT MAKES §05's INDEPENDENCE RULE CHECKABLE. *Verifier ≠ Coder family* is
  the one decision the method rests on — the Tester and the Reviewer exist to
  disagree with the Coder, and two roles from the same family share its blind
  spots. It has lived in prose since it was decided: method §05, and decision
  log P0-3. `violations` is the first thing that can fail on it.

  This is the same gap `:shapes` and `:deps-sigs` had, in the rule that matters
  most. A rule no code can check is a rule that quietly stops being true.

  WHAT IS CHECKED WHERE, and why it is split three ways:

    STRUCTURE (default) — shape, and the independence rule. Pure data, offline,
    deterministic, the same answer on every machine. Safe in `bb gates`.

    ENVIRONMENT (`:env?`) — is the seat installed, is each named key variable
    set. True or false depending on whose laptop this is, so a gate that ran it
    would fail for a contributor who uses a different seat.

    REACHABILITY (`:probe?`) — does each endpoint answer. Network I/O. `bb
    gates` must pass on a plane.

  Nothing here reads a key. `:key-env` names a variable and this checks it is
  set; the value never enters a log, a report, or an ex-info."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [harness.doctor :as doctor]
   [harness.shapes :as shapes]))

(def project-path
  "A project's OWN profile. Absent in this repository on purpose: the kit is
  worked ON rather than dispatched through (root CLAUDE.md), so it has no seat
  and no roles to configure."
  "resources/profile.edn")

(def examples-dir
  "The seed's worked examples, one per seat.

  TWO OF THEM, AND THAT IS THE POINT. A single committed profile reads as
  *your* configuration — which is exactly how the first version of this file
  was misread, and it had the Coder on Anthropic under a Google seat, so the
  misreading landed on a mistake. Two profiles cannot both be yours.

  They are near mirror images: under :claude, Anthropic writes and Google
  reviews; under :agy-ide, Google writes and Anthropic reviews. Between them
  both `:shape` adapters are exercised."
  "resources/profiles")

(def verifiers
  "The roles whose job is to disagree with the Coder. Both derive from the
  same Blueprint slice and neither may share the family that wrote the code."
  [:tester :reviewer])

(defn read-profile
  "The profile at `path`, or a throw naming the file.

  A missing profile is not a violation — it is a project that has not written
  one yet, and `violations` has nothing to say about a file that is not there.
  Reading is separate so the caller decides which that is."
  ([] (read-profile project-path))
  ([path]
   (when-not (fs/exists? path)
     (throw (ex-info "no profile" {:profile/error :no-file :path (str path)})))
   (try
     (edn/read-string (slurp (str path)))
     (catch Exception e
       (throw (ex-info "profile is not readable EDN"
                       {:profile/error :unreadable :path (str path)} e))))))

;; ---------------------------------------------------------------------------
;; structure — pure, offline, the same on every machine
;; ---------------------------------------------------------------------------

(defn- structural
  [{:keys [roles] :as profile}]
  (if-let [errs (shapes/explain-profile profile)]
    ;; One violation, not many: a profile that does not conform has no roles to
    ;; ask questions about, and every later check would report noise derived
    ;; from the same fault.
    [{:profile/error :invalid
      :detail "does not match the Profile schema"
      :errors errs}]
    (let [coder (get-in roles [:coder :family])]
      (vec (for [r verifiers
                 :when (= coder (get-in roles [r :family]))]
             {:profile/error :verifier-shares-coder-family
              :role r
              :detail (str (name r) " is " coder ", the same family as the coder"
                           " — method §05 and decision log P0-3: a verifier that"
                           " shares the Coder's family shares its blind spots")})))))

;; ---------------------------------------------------------------------------
;; environment — true or false depending on whose machine this is
;; ---------------------------------------------------------------------------

(defn probe-tool
  "Probe one toolchain spec. Pulled out and injectable for the same reason
  `doctor/probe` takes its index and pins as arguments: the alternative is a
  test that asserts something about the machine it happens to run on, and the
  seat-unavailable path would otherwise be exercised only by not having your
  own seat installed."
  [spec]
  (doctor/probe spec (doctor/bbin-index) (doctor/mise-pins)))

(defn- seat-violations
  [seat probe]
  (if-let [spec (first (filter #(= seat (:tool %)) doctor/toolchain))]
    (let [{:keys [status]} (probe spec)]
      (when-not (= :ok status)
        [{:profile/error :seat-unavailable
          :detail (str (name seat) " is the seat and `bb doctor` reports it "
                       (name status))}]))
    [{:profile/error :seat-unknown
      :detail (str "no tool named " (name seat) " in harness.doctor/toolchain"
                   " — the seat has to be something the doctor can probe")}]))

(defn- key-violations
  [roles]
  (vec (for [[r {:keys [key-env]}] roles
             :when (and key-env (str/blank? (System/getenv key-env)))]
         {:profile/error :key-env-unset
          :role r
          ;; The variable's NAME, never a value, and never a hint about one.
          :detail (str key-env " is named by " (name r) " and is not set")})))

;; ---------------------------------------------------------------------------
;; reachability — network
;; ---------------------------------------------------------------------------

(defn reachable?
  "Whether `endpoint` answers at all.

  ANY HTTP RESPONSE COUNTS, 401 included. The question is whether the host is
  there and serving, not whether this profile is authorised — authorisation is
  what `:key-env` is about, and conflating the two would report a missing key
  as a dead endpoint. `:throw false` because a 4xx is an answer."
  [endpoint]
  (try
    (some? (:status (http/get endpoint {:throw false :timeout 5000})))
    (catch Exception _ false)))

(defn- endpoint-violations
  [roles]
  (vec (for [[endpoint rs] (group-by #(get-in (val %) [:endpoint]) roles)
             :when (not (reachable? endpoint))]
         {:profile/error :endpoint-unreachable
          :role (mapv key rs)
          :detail (str endpoint " did not answer")})))

;; ---------------------------------------------------------------------------

(defn violations
  "Everything wrong with `profile`, as data.

  Structural checks always run. `:env?` adds the seat and key-variable checks;
  `:probe?` adds one request per distinct endpoint. Both are off by default so
  that the default is the answer that does not depend on the machine.

  A structural failure short-circuits: a profile that is not a Profile has no
  roles to ask about, and the rest would report noise derived from one fault.

  `:probe-tool` replaces how the seat is looked up, for tests."
  ([profile] (violations profile nil))
  ([profile {:keys [env? probe?] probe-tool* :probe-tool}]
   (let [structure (structural profile)]
     (if (seq structure)
       structure
       (let [roles (:roles profile)]
         (cond-> []
           env? (into (seat-violations (:seat profile) (or probe-tool* probe-tool)))
           env? (into (key-violations roles))
           probe? (into (endpoint-violations roles))))))))

(defn verify!
  "`violations`, but throwing — the same shape as `sigs/verify!` and
  `provision/assemble!`. A profile is read once at the top of a run, and a
  wrong one dispatches every role to the wrong place."
  ([profile] (verify! profile nil))
  ([profile opts]
   (let [v (violations profile opts)]
     (when (seq v)
       (throw (ex-info "invalid profile" {:violations v})))
     v)))

(defn summary
  "The profile as lines a human can check at a glance: the seat, then each
  role's family, model and endpoint. Independence is the thing being eyeballed,
  so family comes before model."
  [{:keys [seat roles]}]
  (into [(str "seat  " (name seat))]
        (for [r [:coder :tester :reviewer]
              :let [{:keys [family model endpoint]} (get roles r)]]
          (format "%-9s %-11s %-34s %s" (name r) (name family) model endpoint))))

(defn examples
  "Every worked example the seed ships, as `{seat-name profile}`.

  Keyed by the FILE's name rather than the profile's `:seat`, so a file
  whose name and seat disagree is visible rather than silently deduplicated
  — a test asserts they match."
  ([] (examples examples-dir))
  ([dir]
   (into (sorted-map)
         (for [f (sort (fs/glob dir "*.edn"))]
           [(str/replace (fs/file-name f) #"\.edn$" "")
            (read-profile f)]))))

(defn -main
  "bb profile — check a profile.

  With no arguments: this project's own `resources/profile.edn` if it has
  one, and otherwise every worked example the seed ships, structurally. The
  header says which, because printing a template as though it were your
  configuration is how the first version of this file was misread.

  `--seat <name>` picks one shipped example and adds the environment checks;
  `--file <path>` takes any profile; `--probe` adds one request per endpoint.
  Exits non-zero on any violation, so it composes into a shell the way the
  gates do."
  [& args]
  (let [flags (set args)
        arg (fn [f] (second (drop-while #(not= f %) args)))
        opts {:env? true :probe? (contains? flags "--probe")}
        report (fn [label profile opts]
                 (println (str "  " label))
                 (doseq [l (summary profile)] (println (str "  " l)))
                 (println)
                 (let [v (violations profile opts)]
                   (doseq [{:keys [profile/error detail]} v]
                     (println (str "  " (name error) " — " detail)))
                   v))]
    (cond
      (arg "--file")
      (let [v (report (arg "--file") (read-profile (arg "--file")) opts)]
        (when (seq v) (System/exit 1)))

      (arg "--seat")
      (if-let [profile (get (examples) (arg "--seat"))]
        (let [v (report (str examples-dir "/" (arg "--seat")
                             ".edn — a worked example, not this project's config")
                        profile opts)]
          (when (seq v) (System/exit 1)))
        (do (println (str "  no example for seat " (arg "--seat")
                          " — have " (str/join ", " (keys (examples)))))
            (System/exit 1)))

      (fs/exists? project-path)
      (let [v (report (str project-path " — this project's profile")
                      (read-profile project-path) opts)]
        (when (seq v) (System/exit 1)))

      :else
      ;; No profile of its own. Structural checks only: whether a SEAT is
      ;; installed or a KEY is set says something about this machine, and
      ;; nothing about an example that was never meant to run here.
      (let [all (examples)
            bad (into {} (keep (fn [[k p]] (when-let [v (seq (violations p))] [k (vec v)])) all))]
        (println (str "  No profile at " project-path ". This repository runs no"))
        (println "  dispatch loop (see CLAUDE.md); what follows is the seed's worked")
        (println (str "  examples in " examples-dir "/, checked structurally.\n"))
        (doseq [[nm p] all]
          (println (format "  %-9s %-10s coder %-10s tester %-10s reviewer %s"
                           nm (if (bad nm) "FAILS" "ok")
                           (name (get-in p [:roles :coder :family]))
                           (name (get-in p [:roles :tester :family]))
                           (name (get-in p [:roles :reviewer :family])))))
        (when (seq bad)
          (println)
          (doseq [[nm vs] bad, {:keys [profile/error detail]} vs]
            (println (str "  " nm ": " (name error) " — " detail)))
          (System/exit 1))))))
