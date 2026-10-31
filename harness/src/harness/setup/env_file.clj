(ns harness.setup.env-file
  "The project's `.env`'s names - one owner (plan 7.0, item 8.3d): the template `bb self-check` writes
  in the folder above the KIT's clone when there is none, and the names a seat needs that the file
  lacks, which `bb init --seat` says. `bb self-check` is the file's one writer; `bb init` never writes
  it - an acceptance of the file is keyed by its sha256 (the toolchain pack's `toolchain-env`), so a
  line appended by init would stop an accepted `.env` loading until `bb env-accept` again (From Opus
  5.5's read of the 8.3d design)."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [harness.models.profile :as profile]))

(def default-seat
  "The seat `bb init` assumes when `--seat` is not given, and whose names `bb self-check`'s template
  carries: the one the KIT is proved in first. The others are `resources/profiles/`' other examples."
  "claude")

(defn shipped-profile
  "Where the KIT at `kit-dir` ships the worked example for `seat`, whether or
  not it is there - `survey` says whether, and `refusals` names the ones that are."
  [kit-dir seat]
  (str (fs/path kit-dir "harness" profile/examples-dir (str seat ".edn"))))

(defn- key-uses
  "Every `:key-env` the profile names - its roles and its security panel - to what uses it:
  `{name [[use role-map] ...]}`; a role with no `:key-env` (a local model) names none."
  [profile]
  (->> (concat (for [[role r] (sort-by key (:roles profile))] [(name role) r])
               (for [m (profile/security-panel profile)] [(str "security panel (" (:name m) ")") m]))
       (filter (fn [[_ r]] (:key-env r)))
       (group-by (fn [[_ r]] (:key-env r)))))

(defn env-template
  "The project's `.env` as `bb self-check` writes it (plan 7.0, items 8.6b and 8.3d; the person,
  2026-10-28: \"one of the steps needs to be to initialize a '.env' file with just the KEYS so that
  the Adopter can put in the relevant keys - basically a '.env' template\"): every distinct `:key-env`
  the `seat`'s profile names - its roles and its security panel - one `NAME=` line each, EMPTY, sorted
  by name, under a comment naming what uses it and where it is sent; a role with no `:key-env` (a
  local model) adds no line. The header says the file is this project's keys, one key per project
  with a limit at the provider, never committed. Nil for a profile that names no key. Pure."
  [profile seat]
  (let [by-key (key-uses profile)
        host #(some-> % java.net.URI. .getHost)]
    (when (seq by-key)
      (str (str/join "\n" [(str "# THIS PROJECT'S KEYS - written by `bb self-check` with the names the " seat " seat's profile needs,")
                           "# each empty: put the key after the `=`, nothing else on the line (`bb init --seat <name>` says what"
                           "# another seat needs). One key per project, with a spending limit set at the provider's console"
                           "# (for an OpenRouter key, `bb balance` shows the limit beside the account's credit). This folder"
                           "# is not a repository, and nothing commits this file. Loaded into the shell before the session"
                           "# starts: `eval \"$(bb toolchain-env)\"`, then `claude` - the file is checked first, and no value"
                           "# is ever printed."])
           "\n"
           (str/join (for [k (sort (keys by-key))
                           :let [rs (by-key k)]]
                       (str "\n# " (str/join ", " (distinct (map first rs)))
                            " - " (str/join ", " (distinct (keep (comp host :endpoint second) rs))) "\n"
                            k "=\n")))))))

(defn env-names
  "The names an `.env`'s `text` gives a line to, `export` or not, comments and blank lines aside - pure."
  [text]
  (set (keep #(second (re-matches #"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=.*" %))
             (str/split-lines (or text "")))))

(defn missing-names
  "The `:key-env`s `profile` names that the `.env`'s `env-text` gives no line to, sorted - pure."
  [profile env-text]
  (vec (sort (remove (env-names env-text) (keys (key-uses profile))))))

(defn init-lines
  "What `bb init` says about the `.env` at `path` - pure over its `text` (nil: no file) and the seat's
  `profile`: no file, that `bb self-check` writes it; names the seat needs and the file lacks, each to
  add; else nothing. Never a write."
  [path text profile]
  (cond
    (nil? text) [(str "no .env in " (fs/parent path) ": `bb self-check` writes the template")]
    :else (when-let [ms (seq (missing-names profile text))]
            [(str "add " (str/join ", " ms) " to " path " - fill " (if (next ms) "them" "it"))])))
