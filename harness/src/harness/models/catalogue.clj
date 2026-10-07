(ns harness.models.catalogue
  "The models a route can reach, from OpenRouter's public listing: `bb models <query>`.

  Every brief so far verified its slugs by hand against the same listing - is the
  model there, under which provider tags, at what price, with what context. This
  is that lookup as a command and as a function the bake-off's expander calls:
  a word (\"grok\") or a slug, and back come the models it names, newest first,
  with the family read off the slug's prefix, the prices per million tokens,
  whether the model takes a reasoning parameter, and - for one model - the
  providers serving it. The route a family takes (endpoint, key variable,
  shape, provider pin, effort levels) is `resources/routes.edn`, what the two
  shipped profiles already say, read by `route`.

  NO KEY IS SENT: the listing is public. Nothing here spends money.

  The pure parts are `matching`, `model-row`, `family-of`, `per-million` and
  `route`; `fetch-listing` and `fetch-endpoints` are the two calls; `-main`
  prints."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def default-endpoint "https://openrouter.ai/api/v1")

;; ---------------------------------------------------------------------------
;; routes
;; ---------------------------------------------------------------------------

(def routes-resource "routes.edn")

(defn routes
  "`resources/routes.edn`: family → how it is reached."
  ([] (routes (io/resource routes-resource)))
  ([source] (edn/read-string (slurp source))))

(defn route
  "How `family` is reached, from `routes`; throws by name when no route is written."
  ([family] (route (routes) family))
  ([routes family]
   (or (get routes family)
       (throw (ex-info (str "no route is written for the family " family
                            " (resources/routes.edn has " (str/join ", " (map name (keys routes)))
                            ") - a route nobody wrote is a request nobody checked")
                       {:catalogue/error :no-route :family family})))))

;; ---------------------------------------------------------------------------
;; the listing, as rows
;; ---------------------------------------------------------------------------

(defn family-of
  "The family a slug belongs to: its prefix before the slash, as a keyword
  (`\"x-ai/grok-4.7\"` → `:x-ai`); nil for a slug with no prefix."
  [slug]
  (when-let [[_ p] (re-find #"^([^/]+)/" (or slug ""))]
    (keyword p)))

(defn per-million
  "Dollars per million tokens from the listing's dollars-per-token string (or
  number); nil when absent or unreadable."
  [x]
  ;; Through BigDecimal, not double arithmetic: 0.0000016 × 1e6 in doubles is
  ;; 1.5999999999999999, and a price table that prints that is not read twice.
  (try (when (or (number? x) (string? x))
         (double (* (bigdec (str x)) 1000000M)))
       (catch Exception _ nil)))

(defn alias?
  "OpenRouter lists a few pointers to other entries (`~x-ai/grok-latest`); they
  are not models to name in a profile."
  [id]
  (str/starts-with? (or id "") "~"))

(defn- date-of [epoch-seconds]
  (when (number? epoch-seconds)
    (str (.toLocalDate (java.time.ZonedDateTime/ofInstant
                        (java.time.Instant/ofEpochSecond (long epoch-seconds))
                        java.time.ZoneOffset/UTC)))))

(defn model-row
  "One listing entry as the row the table and the expander use."
  [{:keys [id name created context_length pricing supported_parameters]}]
  {:id id
   :name name
   :family (family-of id)
   :created created
   :date (date-of created)
   :context context_length
   :in (per-million (:prompt pricing))
   :out (per-million (:completion pricing))
   :reasoning? (boolean (some #{"reasoning"} supported_parameters))})

(defn matching
  "The models `query` names in `listing` (the parsed `:data` vector), newest
  first, aliases dropped. An exact id names exactly that model; otherwise the
  query is matched, case-insensitively, against the id and the name. Empty
  when nothing matches - the caller says so."
  [listing query]
  (let [q (str/lower-case (str/trim (or query "")))
        rows (->> listing (remove (comp alias? :id)) (map model-row))
        exact (filter #(= q (str/lower-case (or (:id %) ""))) rows)]
    (if (str/blank? q)
      []                                    ; an empty word names nothing, not everything
      (->> (if (seq exact)
             exact
             (filter #(or (str/includes? (str/lower-case (or (:id %) "")) q)
                          (str/includes? (str/lower-case (or (:name %) "")) q))
                     rows))
           (sort-by :created (fnil > 0 0))
           vec))))

(defn newest
  "The first of `matching`'s answer, or nil."
  [matches]
  (first matches))

;; ---------------------------------------------------------------------------
;; a model's line: the same name with a later version
;; ---------------------------------------------------------------------------

(defn- name-segments
  "The slug's name without its family prefix and any `:variant` suffix, split
  on `-`: `openai/gpt-5.6-sol` → `[\"gpt\" \"5.6\" \"sol\"]`."
  [slug]
  (-> (str slug) (str/replace #"^[^/]+/" "") (str/replace #":.*$" "") (str/split #"-")))

(def ^:private version-segment #"\d+(\.\d+)*")

(defn line-of
  "The line a model belongs to: its name with every version segment removed -
  `openai/gpt-5.6-sol` → `gpt-sol`, `anthropic/claude-opus-5.5` and the direct
  name `claude-opus-5-5` → `claude-opus`, `x-ai/grok-4.7` → `grok`. The
  family is not in it; `newer-in-line` holds that apart."
  [slug]
  (str/join "-" (remove #(re-matches version-segment %) (name-segments slug))))

(defn version-of
  "The version a slug carries, as a vector of integers from every numeric
  segment: `gpt-5.6-sol` → `[5 6]`, `claude-opus-5-5` and `claude-opus-5.5` →
  `[5 5]`, `gpt-6-sol` → `[6]`; `[]` for a slug with none, which no model is
  newer than."
  [slug]
  (vec (for [seg (filter #(re-matches version-segment %) (name-segments slug))
             n (str/split seg #"\.")]
         (parse-long n))))

(defn- version-after?
  "Is version `a` later than `b`? Padded with zeros to the same length, so
  `[6]` is after `[5 6]` and `[6 1]` after `[6]`."
  [a b]
  (let [n (max (count a) (count b))
        pad #(vec (take n (concat % (repeat 0))))]
    (pos? (compare (pad a) (pad b)))))

(defn newer-in-line
  "The models of `listing` in the same line as `slug` with a later version,
  newest version first, as `model-row`s - what a profile's selection has
  behind it in the catalogue. The family must match when the slug names one;
  a direct name (`claude-opus-5-5`) matches by line alone. A `:variant` of a
  model (`:batch`, half price and no completion within a dispatch's wait;
  `:thinking`) is not a model in the line and is left out, as the aliases
  are. Empty for a slug with no version: nothing is in its line by number."
  [listing slug]
  (let [line (line-of slug)
        version (version-of slug)
        family (family-of slug)]
    (if (empty? version)
      []
      (->> listing
           (remove #(or (alias? (:id %)) (str/includes? (str (:id %)) ":")))
           (map model-row)
           (filter #(and (= line (line-of (:id %)))
                         (or (nil? family) (= family (:family %)))
                         (version-after? (version-of (:id %)) version)))
           (sort-by (comp version-of :id) #(if (version-after? %1 %2) -1 (if (= %1 %2) 0 1)))
           vec))))

(defn endpoint-row
  "One serving endpoint of a model as a row: provider, tag, quantization, prices."
  [{:keys [provider_name tag quantization pricing supported_parameters]}]
  {:provider provider_name
   :tag tag
   :quantization quantization
   :in (per-million (:prompt pricing))
   :out (per-million (:completion pricing))
   :reasoning? (boolean (some #{"reasoning"} supported_parameters))})

;; ---------------------------------------------------------------------------
;; the two calls
;; ---------------------------------------------------------------------------

(defn- get-json
  [url]
  (let [{:keys [status body]} (try (http/get url {:throw false :timeout 15000})
                                   (catch Exception e {:status 0 :body (ex-message e)}))]
    (if (<= 200 status 299)
      (json/parse-string body true)
      (throw (ex-info (str "the model listing did not answer: HTTP " status " from " url)
                      {:catalogue/error :unavailable :status status :url url})))))

(defn fetch-listing
  "`GET <endpoint>/models`: the `:data` vector. No key."
  ([] (fetch-listing default-endpoint))
  ([endpoint]
   (:data (get-json (str (str/replace endpoint #"/+$" "") "/models")))))

(defn fetch-endpoints
  "`GET <endpoint>/models/<slug>/endpoints`: the endpoints serving `slug`, as rows."
  ([slug] (fetch-endpoints default-endpoint slug))
  ([endpoint slug]
   (mapv endpoint-row (get-in (get-json (str (str/replace endpoint #"/+$" "") "/models/" slug "/endpoints"))
                              [:data :endpoints]))))

;; ---------------------------------------------------------------------------
;; the command
;; ---------------------------------------------------------------------------

(defn- money [x] (if x (format "$%.2f" (double x)) "—"))

(defn table
  "The matches as lines: newest first and marked, the family, the date, the
  context, the prices per million, whether reasoning is taken, and whether a
  route is written for the family."
  [matches routes]
  (into [(format "  %-40s %-10s %-10s %9s %9s %9s %-9s %s" "model" "family" "listed" "context" "$in/M" "$out/M" "reasoning" "route")]
        (map-indexed
         (fn [i {:keys [id family date context in out reasoning?]}]
           (format "  %-40s %-10s %-10s %9s %9s %9s %-9s %s%s"
                   id (if family (name family) "—") (or date "—") (or context "—")
                   (money in) (money out) (if reasoning? "yes" "no")
                   (if (and family (get routes family)) "yes" "none written")
                   (if (zero? i) "   ← newest" ""))))
        matches))

(defn endpoint-table
  [rows]
  (into [(format "  %-24s %-20s %-12s %9s %9s %s" "provider" "tag" "quantization" "$in/M" "$out/M" "reasoning")]
        (map (fn [{:keys [provider tag quantization in out reasoning?]}]
               (format "  %-24s %-20s %-12s %9s %9s %s" provider tag (or quantization "—") (money in) (money out) (if reasoning? "yes" "no"))))
        rows))

(defn -main
  "bb models <query> [--endpoints] [--endpoint <url>]

  The models the query names, newest first; with `--endpoints`, the providers
  serving the newest one. Exit 1 with a sentence when nothing matches."
  [& args]
  (let [flags (set (filter #(str/starts-with? % "--") args))
        endpoint (or (second (drop-while #(not= % "--endpoint") args)) default-endpoint)
        query (first (remove #(or (str/starts-with? % "--") (= % endpoint)) args))]
    (when (str/blank? query)
      (println "models: which model? bb models <query> - a word (grok) or a slug (x-ai/grok-4.7)")
      (System/exit 2))
    (try
      (let [matches (matching (fetch-listing endpoint) query)]
        (if (empty? matches)
          (do (println (str "models: nothing in the listing matches \"" query "\" - try a shorter word, or the family's prefix"))
              (System/exit 1))
          (do (doseq [l (table matches (routes))] (println l))
              (when (contains? flags "--endpoints")
                (let [m (newest matches)]
                  (println (str "\n  " (:id m) " is served by:"))
                  (doseq [l (endpoint-table (fetch-endpoints endpoint (:id m)))] (println l)))))))
      (catch clojure.lang.ExceptionInfo e
        (println "models:" (ex-message e))
        (System/exit 1)))))
