(ns harness.sigs-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [harness.sigs :as sigs]))

(defn- tree!
  "A temp dir holding `files`, a map of relative path -> source."
  [files]
  (let [dir (str (fs/create-temp-dir))]
    (doseq [[p src] files]
      (fs/create-dirs (fs/path dir (fs/parent p)))
      (spit (str (fs/path dir p)) src))
    dir))

(def store-src
  "(ns a.store)
(def defaults {:limit 10})
(defn query \"doc\" {:added \"1\"} [store q opts] nil)
(defn- secret [x] x)
(defn variadic [a & more] a)
(defn multi ([a]) ([a b]))
(defmulti open :kind)
(def wrapped (fn [x y] x))
(defmacro mac [x] x)
(defrecord R [x])")

(def proto-src
  "(ns a.seam)
(defprotocol Store
  (lookup [this k] \"doc\")
  (put [this k v]))")

(def ctx ["src/a/store.clj" "src/a/seam.clj"])

(defn- both [] (tree! {"src/a/store.clj" store-src "src/a/seam.clj" proto-src}))

;; ---------------------------------------------------------------------------
;; reading the context files
;; ---------------------------------------------------------------------------

(deftest reads-what-the-context-files-define
  (let [{:keys [defs ns-of unparsed]} (sigs/definitions (both) ctx)
        s (get defs 'a.store)]
    (is (= #{} unparsed))
    (is (= 'a.store (get ns-of "src/a/store.clj")))
    (is (= :value (:kind (s 'defaults))))
    (is (= #{3} (:fixed (s 'query))) "a docstring and attr-map are not arities")
    (is (true? (:private? (s 'secret))))
    (is (= 1 (:variadic-min (s 'variadic))))
    (is (= #{1 2} (:fixed (s 'multi))))
    (is (= :open (:kind (s 'open))) "a defmulti's arity is decided by its methods")
    (is (= #{2} (:fixed (s 'wrapped))) "(def f (fn [x y])) is a function, not a value")
    (is (= #{1} (:fixed (s 'mac))))))

(deftest reads-what-a-hand-rolled-reader-would-have-missed
  ;; The reason for reading clj-kondo's analysis instead of parsing the forms:
  ;; it knows what a form EXPANDS to, not only what it looks like.
  (let [s (get (:defs (sigs/definitions (both) ctx)) 'a.store)]
    (is (= :value (:kind (s 'R))) "the record type itself is not callable")
    (is (= #{1} (:fixed (s '->R))) "but its generated constructors are")
    (is (= #{1} (:fixed (s 'map->R))))))

(deftest reads-protocol-methods
  ;; sandbox.bindings is a protocol and nothing else, and a slice's dependency
  ;; on a seam is the one this most needs to get right.
  (let [s (get (:defs (sigs/definitions (both) ctx)) 'a.seam)]
    (is (= #{2} (:fixed (s 'lookup))))
    (is (= #{3} (:fixed (s 'put))))
    (is (= :value (:kind (s 'Store))) "the protocol itself takes no arguments")))

;; ---------------------------------------------------------------------------
;; reading a signature
;; ---------------------------------------------------------------------------

(deftest a-signature-has-two-forms
  (testing "a call signature, whose arity is checked"
    (let [s (sigs/parse-sig '(a.store/query [store q opts]))]
      (is (= 'a.store (:sig/ns s)))
      (is (= 'query (:sig/name s)))
      (is (= 3 (count (:sig/argv s))))))
  (testing "and a name, granting read access to a var that is not a function"
    (is (nil? (:sig/argv (sigs/parse-sig 'a.store/defaults))))
    (is (nil? (:sig/argv (sigs/parse-sig '(a.store/defaults)))))))

(deftest an-unreadable-entry-is-not-an-accusation
  (is (nil? (sigs/parse-sig "a string")))
  (is (nil? (sigs/parse-sig '(:keyword [x]))))
  (is (nil? (sigs/parse-sig 42))))

;; ---------------------------------------------------------------------------
;; the check
;; ---------------------------------------------------------------------------

(defn- v [sigs] (sigs/violations (both) ctx sigs))

(deftest a-truthful-slice-produces-nothing
  (is (= [] (v '[(a.store/query [store q opts])
                 (a.store/variadic [a])
                 (a.store/variadic [a b c])
                 (a.store/multi [a b])
                 (a.store/open [x y z])
                 (a.store/->R [x])
                 (a.seam/lookup [this k])
                 a.store/defaults
                 (query [store q opts])]))
      "including an unqualified name, a protocol method, a record constructor
       and a value"))

(deftest a-call-signature-for-a-value-is-the-run-3-defect
  ;; Run 3 declared (sandbox.expr/operators []) — a zero-arity call signature
  ;; for a map. It rode through packet validation, two dispatches, four gates
  ;; and review, and nothing looked.
  (let [[x & more] (v '[(a.store/defaults [])])]
    (is (empty? more))
    (is (= :not-a-function (:violation x)))
    (is (re-find #"is a value" (:detail x)))))

(deftest the-other-four-kinds
  (is (= :arity-mismatch (:violation (first (v '[(a.store/query [store q])])))))
  (is (= :unknown-var (:violation (first (v '[(a.store/nope [x])])))))
  (is (= :unknown-namespace (:violation (first (v '[(a.other/f [x])])))))
  (is (= :private-var (:violation (first (v '[(a.store/secret [x])]))))
      "a private var cannot be called across the seam, whatever the arity"))

(deftest an-ambiguous-name-is-skipped-rather-than-guessed
  ;; A check that cries wolf about a correct packet gets switched off. Two
  ;; context files defining `dup` leaves no way to say which was meant.
  (let [dir (tree! {"src/a/one.clj" "(ns a.one) (defn dup [x] x)"
                    "src/a/two.clj" "(ns a.two) (defn dup [x y] x)"})
        files ["src/a/one.clj" "src/a/two.clj"]]
    (is (= [] (sigs/violations dir files '[(dup [x y z])])))
    (testing "but qualifying it resolves the ambiguity and the check applies"
      (is (= :arity-mismatch
             (:violation (first (sigs/violations dir files '[(a.one/dup [x y z])]))))))))

(deftest a-context-file-that-is-not-there-is-reported
  ;; Silently not verifying is the failure this namespace exists to end.
  (let [dir (tree! {"src/a/store.clj" store-src})]
    (is (= [:missing-context-file]
           (mapv :violation (sigs/violations dir ctx '[(a.store/query [s q o])]))))))

(deftest a-context-file-that-does-not-parse-is-reported-not-guessed-at
  ;; clj-kondo still emits partial analysis for a file it could not read, and
  ;; using it would report every var it never reached as undefined — the exact
  ;; false accusation this must not make. So: say the file did not parse, and
  ;; say nothing about the signatures that depend on it.
  (let [dir (tree! {"src/a/store.clj" store-src
                    "src/a/seam.clj" "(ns a.seam)\n(defn broken [x"})
        out (sigs/violations dir ctx '[(a.seam/lookup [this k])
                                       (a.store/query [store q])])]
    (is (= #{:unparsed-context-file :arity-mismatch} (set (map :violation out)))
        "the unreadable file is named, the readable one is still checked")
    (is (nil? (some #{:unknown-var :unknown-namespace} (map :violation out)))
        "and nothing is claimed about what the unreadable file defines")))

;; ---------------------------------------------------------------------------
;; what the implementation calls, against the slice (NOTES.md row 11)
;; ---------------------------------------------------------------------------

(defn- with-impl [impl-src]
  (tree! {"src/a/store.clj" store-src "src/a/seam.clj" proto-src "src/a/impl.clj" impl-src}))

(defn- calls [impl-src sigs]
  (sigs/undeclared-calls (with-impl impl-src)
                         {:files/impl ["src/a/impl.clj"] :files/context ctx
                          :blueprint/slice {:deps-sigs sigs}}))

(deftest an-implementation-that-calls-only-its-slice-is-clean
  ;; D8's bind.clj called expr/literal? with no entry naming it, and nothing
  ;; noticed. Core, libraries and the impl's own vars are not seams.
  (is (= [] (calls "(ns a.impl (:require [a.store :as store] [clojure.string :as str]))
(defn- helper [x] (str/upper-case x))
(defn go [q] (helper (store/query nil q {})))"
                   '[(a.store/query [store q opts])]))))

(deftest a-call-the-slice-did-not-grant-is-named-with-its-line
  (let [[v :as vs] (calls "(ns a.impl (:require [a.store :as store]))
(defn go [q]
  (store/query nil q {})
  (store/variadic 1 2))"
                          '[(a.store/query [store q opts])])]
    (is (= 1 (count vs)))
    (is (= :undeclared-call (:violation v)))
    (is (= 'a.store/variadic (:call v)))
    (is (= 4 (:line v)))
    (is (re-find #"impl\.clj" (:file v)))))

(deftest an-arity-the-slices-argv-does-not-accept-is-a-violation
  (is (= [:arity-mismatch]
         (mapv :violation (calls "(ns a.impl (:require [a.store :as store]))
(defn go [q] (store/query q))" '[(a.store/query [store q opts])]))))
  (testing "a variadic argv accepts anything from its fixed count up, and a bare name any arity"
    (is (= [] (calls "(ns a.impl (:require [a.store :as store]))
(defn go [q] (store/variadic q 1 2 3))" '[(a.store/variadic [a & more])])))
    (is (= [] (calls "(ns a.impl (:require [a.store :as store]))
(defn go [q] (store/query q))" '[a.store/query])))))

(deftest a-rewrite-calling-its-own-namespace-is-not-a-seam-crossing
  ;; A rewrite's own file can be in :files/context (the Architect showing the
  ;; old implementation); its calls to itself are not calls into a seam.
  (let [dir (with-impl "(ns a.impl (:require [a.store :as store]))
(defn- helper [x] x)
(defn go [q] (helper (store/query nil q {})))")]
    (is (= [] (sigs/undeclared-calls dir {:files/impl ["src/a/impl.clj"]
                                          :files/context (conj ctx "src/a/impl.clj")
                                          :blueprint/slice {:deps-sigs '[(a.store/query [store q opts])]}})))))

(deftest a-call-outside-the-context-is-not-this-checks-business
  ;; gate 4 catches a namespace outside the layer; this only judges calls into
  ;; the namespaces the packet showed.
  (is (= [] (calls "(ns a.impl (:require [clojure.set :as set]))
(defn go [q] (set/union q q))" []))))

(deftest a-missing-impl-file-is-reported-not-skipped
  (is (= [:missing-impl-file]
         (mapv :violation (sigs/undeclared-calls (both) {:files/impl ["src/a/impl.clj"] :files/context ctx
                                                         :blueprint/slice {:deps-sigs []}})))))

(deftest verify-throws-so-a-wrong-packet-cannot-reach-an-agent
  (let [dir (both)]
    (is (= [] (sigs/verify! dir ctx '[(a.store/query [s q o])])))
    (is (thrown-with-msg? Exception #"do not match :files/context"
                          (sigs/verify! dir ctx '[(a.store/defaults [])])))))

;; ---------------------------------------------------------------------------
;; what depends on a file a task will rewrite
;; ---------------------------------------------------------------------------

(defn- project []
  (tree! {"src/app/render.clj" "(ns app.render)\n(defn render [e] (str e))"
          "src/app/api.clj" "(ns app.api (:require [app.render :as r]))\n(defn show [e] (r/render e))"
          "src/app/both.clj" "(ns app.both (:require [app.render] [app.store]))"
          "src/app/store.clj" "(ns app.store)"
          "src/app/unrelated.clj" "(ns app.unrelated (:require [app.store]))"
          "test/app/render_test.clj" "(ns app.render-test (:require [app.render :as r]))"
          "test/app/api_test.clj" "(ns app.api-test (:require [app.api]))"}))

(deftest two-context-files-with-the-same-name-are-both-read
  ;; Review R1: definitions matched clj-kondo's filenames to paths by bare file
  ;; name, so src/a/web/core.clj shadowed src/a/util/core.clj and a correct
  ;; signature for a.util.core was reported :unknown-namespace. Reached from a
  ;; dependent that `with-dependents` added, with nothing the Architect wrote.
  (let [dir (tree! {"src/a/util/core.clj" "(ns a.util.core)\n(defn f [x] x)"
                    "src/a/web/core.clj" "(ns a.web.core (:require [a.util.core]))\n(defn g [y] y)"})
        both ["src/a/util/core.clj" "src/a/web/core.clj"]]
    (testing "both namespaces are defined, each under its own path"
      (is (= {"src/a/util/core.clj" 'a.util.core "src/a/web/core.clj" 'a.web.core}
             (:ns-of (sigs/definitions dir both)))))
    (testing "a signature in either checks out"
      (is (= [] (sigs/violations dir both '[(a.util.core/f [x]) (a.web.core/g [y])]))))
    (testing "and through the dependents path that exposed it"
      (let [spec (sigs/with-dependents {:files/context ["src/a/util/core.clj"]}
                   (sigs/dependents dir ["src/a/util/core.clj"]))]
        (is (= both (:files/context spec)))
        (is (= [] (sigs/violations dir (:files/context spec) '[(a.util.core/f [x])])))))))

(deftest a-rewrite-is-shown-what-requires-it
  ;; D12: the first dispatched rewrite broke test/sandbox/property_test.clj,
  ;; which requires sandbox.render and was in no packet.
  (let [dir (project)]
    (testing "direct dependents in src and test, sorted, each once"
      (is (= ["src/app/api.clj" "src/app/both.clj" "test/app/render_test.clj"]
             (sigs/dependents dir ["src/app/render.clj"]))))
    (testing "not transitive: api_test requires api, not render"
      (is (not-any? #{"test/app/api_test.clj"} (sigs/dependents dir ["src/app/render.clj"]))))
    (testing "several impl files: the union, each file once, never an impl file itself"
      ;; api.clj requires render.clj but is itself being rewritten, so it is not
      ;; a dependent; both.clj requires two impl namespaces and appears once.
      (is (= ["src/app/both.clj" "src/app/unrelated.clj" "test/app/api_test.clj" "test/app/render_test.clj"]
             (sigs/dependents dir ["src/app/render.clj" "src/app/api.clj" "src/app/store.clj"]))))
    (testing "a namespace being created has no dependents yet"
      (is (= [] (sigs/dependents dir ["src/app/brand_new.clj"]))))
    (testing "a scan path that does not exist is skipped, not an error"
      (is (= ["src/app/api.clj" "src/app/both.clj"]
             (sigs/dependents dir ["src/app/render.clj"] ["src" "no-such-dir"]))))))

(deftest a-tasks-own-test-file-is-not-its-dependent
  ;; Review R2: dependents excluded :files/impl but not :files/test, so a rewrite
  ;; with existing tests listed the Tester's own target as a file to keep working.
  (let [dir (project)
        spec {:files/impl ["src/app/render.clj"] :files/test ["test/app/render_test.clj"]}]
    (is (some #{"test/app/render_test.clj"} (sigs/dependents dir ["src/app/render.clj"]))
        "the raw query does see it — it does require the namespace")
    (is (= ["src/app/api.clj" "src/app/both.clj"] (sigs/task-dependents dir spec))
        "but a task's dependents leave out its own test file")
    (is (= ["src/app/api.clj" "src/app/both.clj" "test/app/render_test.clj"]
           (sigs/task-dependents dir {:files/impl ["src/app/render.clj"]
                                      :files/test ["test/app/other_test.clj"]}))
        "a test file that is not the task's own is still a dependent")))

(deftest dependents-join-the-context-after-the-architects-entries
  (let [spec {:files/context ["src/app/store.clj" "src/app/api.clj"]}]
    (is (= ["src/app/store.clj" "src/app/api.clj" "test/app/render_test.clj"]
           (:files/context (sigs/with-dependents spec ["src/app/api.clj" "test/app/render_test.clj"])))
        "the Architect's order first, and a file already listed is not repeated")
    (is (= spec (sigs/with-dependents spec [])) "no dependents, no change")
    (is (= spec (sigs/with-dependents spec nil)))))

;; ---------------------------------------------------------------------------
;; what the implementation defines that the Tester never saw (NOTES.md row 5)
;; ---------------------------------------------------------------------------

(deftest impl-names-are-the-vars-the-slice-did-not-grant
  (let [dir (with-impl "(ns a.impl (:require [a.store :as store]))
(defn- helper [x] x)
(def ops {:add +})
(defn go [q] (helper (store/query nil q {})))
(defn extra [q] q)")
        spec {:files/impl ["src/a/impl.clj"] :blueprint/slice {:interfaces '[(go [q])]}}]
    (is (= '[{:ns a.impl :name extra} {:ns a.impl :name helper} {:ns a.impl :name ops}]
           (sigs/impl-names dir spec))
        "the interface `go` is the Tester's to name; the helper, the value and the extra public are not")
    (is (= [] (sigs/impl-names dir (assoc-in spec [:blueprint/slice :interfaces] '[(go [q]) (extra [q]) helper ops])))
        "every form parse-sig reads grants its name")
    (is (= [] (sigs/impl-names dir (assoc spec :files/impl ["src/a/absent.clj"])))
        "an absent impl file contributes nothing — no refusal on a guess")
    (is (= '[{:ns a.impl :name extra} {:ns a.impl :name helper} {:ns a.impl :name ops}]
           (sigs/impl-names dir (assoc spec :files/impl ["src/a/absent.clj" "src/a/impl.clj"])))
        "and an absent one beside a present one is skipped, not handed to clj-kondo")))
