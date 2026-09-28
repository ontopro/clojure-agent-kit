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
            [harness.models.profile :as profile]))

(defn- get-data
  "`GET <endpoint>/<path>` with the key, as the parsed `:data` map, or
  `{:unavailable reason}`."
  [endpoint key path]
  (let [url (str (str/replace endpoint #"/+$" "") path)
        {:keys [status body]} (try (http/get url {:headers {"Authorization" (str "Bearer " key)}
                                                  :throw false :timeout 8000})
                                   (catch Exception _ {:status 0}))]
    (if (<= 200 status 299)
      (:data (json/parse-string body true))
      {:unavailable (str "HTTP " status)})))

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
  key's is the one a person set."
  [endpoint key-env]
  (let [key (System/getenv key-env)]
    (if (str/blank? key)
      {:unavailable (str key-env " is unset")}
      (let [d (get-data endpoint key "/key")]
        (if (:unavailable d)
          d
          (let [c (get-data endpoint key "/credits")]
            (cond-> {:limit (:limit d) :usage (:usage d) :remaining (:limit_remaining d)}
              (not (:unavailable c))
              (assoc :credits {:total (:total_credits c) :usage (:total_usage c)
                               :remaining (when (and (:total_credits c) (:total_usage c))
                                            (- (:total_credits c) (:total_usage c)))}))))))))

(defn openrouter-role
  "The first role in `profile` served by OpenRouter, or nil."
  [profile]
  (->> (vals (:roles profile))
       (filter #(some-> (:endpoint %) (str/includes? "openrouter")))
       first))

(defn spend-by-provider
  "`:step/cost` summed per `:step/provider` over `steps` (report steps, as a run
  record or `state.edn` carries them). Steps with no cost count for nothing — the
  total is a floor, as every cost figure here is."
  [steps]
  (reduce (fn [acc {:step/keys [cost provider]}]
            (if cost (update acc (or provider "unknown") (fnil + 0) cost) acc))
          {} steps))

(defn- money [x] (if x (format "$%.2f" (double x)) "—"))

(defn line
  "One line for the console. `profile` finds the OpenRouter key; `steps` are
  the run's steps so far (nil before `start`)."
  [profile steps]
  (let [or-role (openrouter-role profile)
        status (when or-role (openrouter-key-status (:endpoint or-role) (:key-env or-role)))
        spend (spend-by-provider steps)
        ;; "Anthropic API" is the provider a DIRECT dispatch records; an Anthropic model served
        ;; through OpenRouter records its serving provider ("Anthropic"), and that spend is
        ;; already in the key's figure. This once summed both and called the sum "Anthropic
        ;; spend" on a project where no role was on Anthropic's API at all.
        anthropic (get spend "Anthropic API" 0)
        direct? (some #(some-> (:endpoint %) (str/includes? "api.anthropic.com")) (vals (:roles profile)))]
    (str "balance: OpenRouter "
         (cond (nil? or-role) "not in this profile"
               (:unavailable status) (str "unavailable (" (:unavailable status) ")")
               :else (str "key " (money (:remaining status)) " remaining of its " (money (:limit status))
                          " limit (" (money (:usage status)) " used)"
                          (if-let [c (:credits status)]
                            (str "; account credit " (money (:remaining c)) " remaining of "
                                 (money (:total c)) " bought - the number a cap is really against")
                            "; account credit unavailable")))
         (if direct?
           (str " · Anthropic API spend this run " (money anthropic)
                " (its API has no balance endpoint; the console has it)")
           " · no role is on Anthropic's API directly, so the key's usage is the whole spend"))))

(defn- profile-file?
  "Whether `path` reads as a profile - a map with `:roles` - rather than as a
  record. A record has `:run/steps` or `:steps` and no roles, so the two are
  told apart by what is in the file, not by its name."
  [path]
  (boolean (and path (fs/regular-file? path)
                (try (contains? (edn/read-string (slurp path)) :roles)
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
  a profile argument, the workspace's `<plan>/profile.edn`."
  [& args]
  (let [[profile-path files] (split-args args)]
    (when-not profile-path
      (println (str "balance: no profile - pass one (bb balance <profile.edn> [records...]), or run from "
                    "the clone of a workspace whose plan has profile.edn"))
      (System/exit 1))
    (let [prof (profile/read-profile profile-path)
          steps (mapcat #(let [m (edn/read-string (slurp %))] (or (:run/steps m) (:steps m))) files)]
      (println (line prof steps)))))
