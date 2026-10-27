(ns harness.money.balance
  "What the money looks like before and after a run: `bb balance`, and the two
  lines `bb run-loop run` prints around the loop.

  TWO PROVIDERS, TWO DIFFERENT FACTS, and the line says which is which.
  OpenRouter exposes its key's limit, usage and remaining credit
  (`GET /api/v1/key`), so that is a BALANCE. Anthropic's API has no balance
  endpoint — the console has it, the API does not — so for Anthropic the line
  is SPEND: what this run's records say its Anthropic-served steps cost. The
  first project built on this kit stopped once on each provider's credit,
  mid-run, with nothing having warned; this is the warning, and it is honest
  about the half it cannot see.

  Never prints a key. Never fails a run: a provider that does not answer
  prints `unavailable`."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [harness.models.catalogue :as catalogue]
            [harness.models.profile :as profile]
            [harness.models.provenance :as provenance]
            [harness.money.report :as report]))

(defn read-data
  "An answer `{:status :body}` as its parsed `:data` map, or `{:unavailable reason}`. A provider
  that answers with a body that is not JSON, or with no `:data` map, is unavailable, said as such:
  `bb balance`, a run's `start` and `bb run-loop run` read this, and a proxy's HTML page once
  could have thrown through all three.

  THE REASON IS THE KIT'S OWN WORDS. A provider's `:data` carrying an `:unavailable` of its own had
  been returned as the reason, verbatim, into the log and the run record - with whatever it echoed:
  the data's own `:unavailable` is dropped, and only a number is read from the rest."
  [{:keys [status body reason]}]
  (let [status (or status 0)
        data (when (<= 200 status 299)
               (try (:data (json/parse-string (str body) true)) (catch Exception _ ::unreadable)))]
    (cond (zero? status) {:unavailable (str "no answer" (when reason (str ": " reason)))}
          (not (<= 200 status 299)) {:unavailable (str "HTTP " status)}
          (= ::unreadable data) {:unavailable (str "HTTP " status ", a body that is not JSON")}
          (not (map? data)) {:unavailable (str "HTTP " status ", no data in the answer")}
          :else (dissoc data :unavailable))))

(defn- get-data
  "`GET <endpoint>/<path>` with the key, as the parsed `:data` map, or
  `{:unavailable reason}` (`read-data`) - the key redacted to `<key-env>` from both: an exception's
  message is the client's, and may quote what it sent."
  [endpoint key key-env path]
  (let [url (str (str/replace endpoint #"/+$" "") path)]
    (-> (read-data (try (http/get url {:headers {"Authorization" (str "Bearer " key)}
                                       :throw false :timeout 8000})
                        (catch Exception e {:status 0 :reason (or (ex-message e) (.getSimpleName (class e)))})))
        (provenance/redact key key-env))))

(defn- number-or-nil
  "`x` when it is a number, else nil: a provider's field may be absent, null or not a number."
  [x]
  (when (number? x) x))

(defn- host
  "The host of `endpoint`, or nil when it is not a string or does not parse as a URI: a routes file is
  read, not trusted, and one bad entry must not stop the lookup of the rest."
  [endpoint]
  (when (string? endpoint)
    (try (.getHost (java.net.URI. endpoint))
         (catch java.net.URISyntaxException _ nil))))

(defn openrouter-route
  "OpenRouter's endpoint and its key's variable as `routes` (`routes.edn`) names them, when `endpoint`
  is that endpoint exactly; nil otherwise.

  ASKED ONLY OF THE ENDPOINT THE ROUTES NAME, WITH THE ROUTES' KEY. A role's endpoint was taken for
  OpenRouter's when it CONTAINED the word, and its own `:key-env` was sent: a run record handed to
  `bb balance` as its profile sent any variable to `https://elsewhere/openrouter/`."
  ([endpoint] (openrouter-route endpoint (catalogue/routes)))
  ([endpoint routes]
   (some (fn [r] (when (and (map? r) (= endpoint (:endpoint r)) (:key-env r)
                            (= "openrouter.ai" (host (:endpoint r))))
                   (select-keys r [:endpoint :key-env])))
         (vals routes))))

(defn openrouter-key-status
  "`{:limit :usage :remaining}` in dollars for the key in `key-env`, plus
  `:credits {:total :usage :remaining}` for the ACCOUNT when its endpoint
  answers, or `{:unavailable reason}`. `endpoint` is the role's OpenRouter
  endpoint.

  TWO NUMBERS, AND THEY ARE NOT THE SAME. `/key` gives the key's spending
  limit and what is left of it; `/credits` gives the account's purchased
  credit and its usage. A key limited to $80 on an account holding $30 stops
  at $30, and the line once showed only the key's figure and called it the
  balance. The account's is the one a cap is really checked against; the
  key's is the one a person set.

  ANY FIGURE MAY BE MISSING. A key with no limit has no limit and no remaining; a field the
  provider leaves out, or sends as something other than a number, is nil here, and the line and
  the report name it rather than compute with it."
  [endpoint _key-env]
  (let [route (openrouter-route endpoint)
        key-env (:key-env route)
        key (some-> key-env System/getenv)]
    (cond
      (nil? route)
      {:unavailable (str endpoint " is not OpenRouter's endpoint as routes.edn names it - not asked, so no key is sent")}

      (str/blank? key)
      {:unavailable (str key-env " is unset")}

      :else
      (let [d (get-data endpoint key key-env "/key")]
        (if (:unavailable d)
          d
          (let [c (get-data endpoint key key-env "/credits")]
            (cond-> {:limit (number-or-nil (:limit d)) :usage (number-or-nil (:usage d)) :remaining (number-or-nil (:limit_remaining d))}
              (not (:unavailable c))
              (assoc :credits (let [t (number-or-nil (:total_credits c)) u (number-or-nil (:total_usage c))]
                                {:total t :usage u :remaining (when (and t u) (- t u))})))))))))

(defn openrouter-role
  "The first role in `profile` served by OpenRouter - a security reviewer panel's members among them - or nil."
  [profile]
  (->> (map second (profile/role-blocks profile))
       (filter #(openrouter-route (:endpoint %)))
       first))

(defn spend-by-provider
  "`:step/cost` summed per `:step/provider` over `steps` (report steps, as a run
  record or `state.edn` carries them). Steps with no cost count for nothing — the
  total is a floor, as every cost figure here is."
  [steps]
  (reduce (fn [acc {:step/keys [cost provider]}]
            (if cost (update acc (or provider "unknown") (fnil + 0) cost) acc))
          {} steps))

(defn unpriced-by-provider
  "How many dispatch steps per `:step/provider` carry no cost: a direct provider's spend is the
  run's priced steps, and one with none priced read as $0.00."
  [steps]
  (frequencies (keep #(when (and (= :dispatch (:step/kind %)) (nil? (:step/cost %)) (:step/provider %)) (:step/provider %)) steps)))

(defn- money [x] (if (number? x) (format "$%.2f" (double x)) "—"))

(defn key-text
  "What the line says of a key's `status` (`openrouter-key-status`, available): what is left of
  its limit, or that it has none - a key with no limit has no remaining - and what it has used."
  [status]
  (str "key "
       (if (number? (:limit status))
         (str (money (:remaining status)) " remaining of its " (money (:limit status)) " limit")
         "with no limit")
       " (" (money (:usage status)) " used)"))

(defn line
  "One line for the console. `profile` finds the OpenRouter key; `steps` are
  the run's steps so far (nil before `start`)."
  [profile steps]
  (let [or-role (openrouter-role profile)
        status (when or-role (openrouter-key-status (:endpoint or-role) (:key-env or-role)))
        spend (spend-by-provider steps)
        unpriced (unpriced-by-provider steps)
        ;; "Anthropic API" is the provider a DIRECT dispatch records; an Anthropic model served
        ;; through OpenRouter records its serving provider ("Anthropic"), and that spend is
        ;; already in the key's figure. This once summed both and called the sum "Anthropic
        ;; spend" on a project where no role was on Anthropic's API at all.
        ;; EVERY DIRECT PROVIDER the profile's roles reach, by the name a direct dispatch records
        ;; (`provenance/provider-of-endpoint`): none has a balance endpoint, so the run's own spend
        ;; is what can be said. Anthropic alone was named once, and a project's direct Gemini
        ;; role spent with nothing saying so.
        direct (sort (distinct (keep #(provenance/provider-of-endpoint (:endpoint %)) (map second (profile/role-blocks profile)))))]
    (str "balance: OpenRouter "
         (cond (nil? or-role) "not in this profile"
               (:unavailable status) (str "unavailable (" (:unavailable status) ")")
               :else (str (key-text status)
                          (if-let [c (:credits status)]
                            (str "; account credit " (money (:remaining c)) " remaining of "
                                 (money (:total c)) " bought - the number a cap is really against")
                            "; account credit unavailable")))
         (if (seq direct)
           (str " · direct spend this run: " (str/join ", " (for [p direct] (str p " " (money (get spend p 0))
                                                                                 (when-let [n (get unpriced p)] (str " (" n " unpriced)")))))
                " (no balance endpoint; each console has it)")
           " · no role is on a provider's API directly, so the key's usage is the whole spend"))))

(defn- profile-file?
  "Whether `path` reads as a profile - a map with `:roles` - rather than as a
  record. A record has `:run/steps` or `:steps` and no roles, so the two are
  told apart by what is in the file, not by its name."
  [path]
  (boolean (and path (fs/regular-file? path)
                (try (let [m (edn/read-string {:default tagged-literal} (slurp path))]
                       ;; a run record is a record whatever else it carries
                       (and (contains? m :roles) (not (some #(contains? m %) [:run/id :run/steps :steps]))))
                     (catch Exception _ false)))))

(defn split-args
  "`bb balance`'s arguments as `[profile-path files]`: the first argument is
  the profile when it is one, and otherwise the profile is the workspace's
  (`profile/project-profile` - nil outside one) and every argument is a record.
  A stop's next-steps line says `bb balance` alone, and from a workspace's clone
  that has to mean this project's profile.

  `project` is that fallback, given so the split is a pure function of its
  arguments: the one-argument form asks the workspace this command runs in,
  and a test that did the same failed in every workspace's clone - the one
  place `bb gates` is documented to run - because there the fallback exists."
  ([args] (split-args args (profile/project-profile)))
  ([args project]
   (if (profile-file? (first args))
     [(first args) (rest args)]
     [project args])))

(defn -main
  "bb balance [profile.edn] [state.edn|run-record.edn ...] — the line above, over
  the steps of every record given (none: the OpenRouter status alone). Without
  a profile argument, the workspace's `<build>/profile.edn`."
  [& args]
  (let [[profile-path files] (split-args args)]
    (when-not profile-path
      (println (str "balance: no profile - pass one (bb balance <profile.edn> [records...]), or run from "
                    "the clone of a workspace whose plan has profile.edn"))
      (System/exit 1))
    (let [prof (profile/read-profile profile-path)
          steps (mapcat #(let [m (report/current (edn/read-string (slurp %)))] (or (:run/steps m) (:steps m))) files)]
      (println (line prof steps)))))
