(ns harness.contract.stub-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.contract.stub :as stub]))

(deftest derives-the-namespace-from-the-path
  (is (= 'sandbox.clamp (stub/ns-for "src/sandbox/clamp.clj")))
  (is (= 'app.some-ns (stub/ns-for "src/app/some_ns.clj"))
      "underscore to hyphen is the file/namespace convention, not a guess")
  (is (= 'a.b.c (stub/ns-for "src/a/b/c.cljc"))))

(deftest every-stubbed-body-throws
  ;; A stub that returned a plausible value would let a test pass against
  ;; nothing — the same failure as an unmarked synthetic number, one layer down.
  (let [s (stub/render 'sandbox.clamp '{:interfaces [(clamp [lo hi x])]})]
    (is (str/includes? s "(ns sandbox.clamp"))
    (is (str/includes? s "GENERATED STUB"))
    (is (str/includes? s "[lo hi x]") "the arity comes from the contract")
    (is (str/includes? s "not implemented"))
    (is (not (str/includes? s "nil")) "no plausible return value anywhere")))

(deftest a-stubbed-body-defeats-thrown-exception
  ;; One run: `(is (thrown? Exception (render e)))` is an ordinary case for a
  ;; function whose job is to refuse some inputs, and it PASSED against a stub
  ;; throwing ex-info — which IS an Exception. Two cases in that suite were
  ;; green before any implementation existed. An AssertionError is an Error,
  ;; so `thrown? Exception` cannot catch it and clojure.test reports an error.
  ;;
  ;; Evaluated, not grepped: the guarantee is about what the generated code
  ;; DOES, and a test that only reads the source would pass on a stub that
  ;; merely mentions AssertionError in a comment.
  (load-string (stub/render 'gen.probe '{:interfaces [(f [x])]}))
  (let [call #((resolve 'gen.probe/f) 1)]
    (is (= :not-an-exception
           (try (call)
                (catch Exception _ :an-exception)
                (catch Throwable _ :not-an-exception))))
    (is (thrown? AssertionError (call))
        "and a test that means to assert the stub is unimplemented still can")))

(deftest an-empty-slice-is-an-error-not-an-empty-file
  (is (thrown-with-msg? Exception #"no interfaces"
                        (stub/render 'x {})))
  (is (= [:interfaces] (mapv :key (stub/check-slice '{:shapes [] :interfaces []})))
      "and `start` learns it from check-slice before provisioning")
  (testing "an interface that is neither a function nor a var is refused by name"
    (is (= '[routes (broken 1 2)]
           (mapv :entry (stub/check-slice '{:interfaces [routes (broken 1 2) (ok [x]) (data)]})))
        "a bare symbol and a list with no arg vector; the function and the var pass")
    (is (thrown-with-msg? Exception #"cannot read: interfaces routes"
                          (stub/render 'x '{:interfaces [routes]})))))

(deftest a-one-element-interface-is-a-var-the-stub-declares
  ;; The fourth project's routing task delivered a `def` of route data and had no form
  ;; for it: a bare symbol crashed provisioning, an empty list was refused. `(routes)` is
  ;; the form `sigs/parse-sig` already reads under :deps-sigs for a var; here it renders
  ;; a `declare`, so the stub loads and any use of the var errors.
  (let [s (stub/render 'gen.var '{:interfaces [(routes) (handler [req])]})]
    (is (str/includes? s "(declare routes)"))
    (is (str/includes? s "(defn handler"))
    (load-string s)
    (is (not (bound? (resolve 'gen.var/routes))) "unbound, so a test that uses it errors")
    (is (thrown? AssertionError ((resolve 'gen.var/handler) {})))))

;; ---------------------------------------------------------------------------
;; shapes
;; ---------------------------------------------------------------------------

(deftest a-shape-the-slice-defines-is-emitted
  ;; The gap this closes: the loop's second end-to-end run produced a slice that
  ;; declared a shape and an implementation that never validated against it, and
  ;; every gate was green over that. A declared shape with no consumer is
  ;; invisible to every tool; an emitted one is something the Tester can load.
  (let [s (stub/render 'sandbox.clamp
                       '{:shapes [[Range [:map [:lo :int] [:hi :int]]]]
                         :interfaces [(clamp [lo hi x])]})]
    (is (str/includes? s "(def Range"))
    (is (str/includes? s "[:map [:lo :int] [:hi :int]]")
        "the schema data verbatim — which library reads it is the project's business")
    (is (str/index-of s "(def Range") "the shape precedes the functions that use it")
    (is (< (str/index-of s "(def Range") (str/index-of s "(defn clamp")))))

(deftest a-big-schema-stays-legible
  ;; `pr-str` puts a :map of any size on one line. cljfmt does not reflow
  ;; long lines, so it passes the fmt gate and is still unreadable — and
  ;; reading it is what the Tester is here to do.
  (let [s (stub/render (quote x)
                       (quote {:shapes [[Analysis
                                         [:map {:closed true}
                                          [:analysis/depth [:int {:min 1}]]
                                          [:analysis/vars [:set [:string {:min 1}]]]
                                          [:analysis/ops
                                           [:map-of [:enum :add :sub] [:int {:min 1}]]]]]]
                               :interfaces [(analyze [e])]}))
        longest (apply max (map count (str/split-lines s)))]
    (is (< longest 80) (str "longest emitted line was " longest " chars"))
    (is (str/includes? s "\n   [:analysis/depth [:int {:min 1}]]")
        "and every entry sits under the first, the way cljfmt indents")))

(deftest a-shape-only-named-is-not-invented
  ;; Bare symbols NAME a shape defined elsewhere — the form shapes/example-packet
  ;; uses for `Release`, beside one it defines inline. There is nothing to emit,
  ;; and emitting a guess would be a fabricated contract, which is exactly what
  ;; :label-fabricated forbids.
  (let [s (stub/render 'sandbox.clamp
                       '{:shapes [Concept Release]
                         :interfaces [(clamp [lo hi x])]})]
    (is (not (str/includes? s "(def Concept")))
    (is (str/includes? s ":files/context: Concept, Release")
        "named in the docstring, with where they actually arrive from"))
  (testing "mixed with a defined one"
    (let [s (stub/render 'x '{:shapes [Concept [R [:map]]]
                              :interfaces [(f [a])]})]
      (is (str/includes? s "(def R"))
      (is (str/includes? s ":files/context: Concept.")))))

(deftest a-slice-with-no-shapes-says-nothing-about-them
  (let [s (stub/render 'x '{:interfaces [(f [a])]})]
    (is (not (str/includes? s ":files/context")))
    (is (not (str/includes? s "(def ")))))

(deftest shape-def-classifies-the-two-forms
  (is (nil? (stub/shape-def 'Concept)))
  (is (nil? (stub/shape-def '[Concept]))
      "a one-element vector names nothing to define")
  (is (nil? (stub/shape-def '[:map [:a :int]]))
      "a bare schema with no name cannot be def-ed")
  (is (str/starts-with? (stub/shape-def '[R [:map]]) "(def R"))
  (is (str/starts-with? (stub/shape-def '(def R [:map])) "(def R")
      "a def form is the pair, written the way an Architect writes it")
  (is (nil? (stub/shape-def '(defn R [] 1)))
      "only def; a defn is not a shape"))

(deftest a-shape-carrying-a-string-renders-a-stub-that-loads
  ;; The fourth project inlined every shape as a `(def …)` form, and one carried an
  ;; :error/message string. The form was not a pair, so it fell through as "named" and
  ;; was joined into the docstring with its quotes — a syntax error the Tester met in six
  ;; of eleven runs. Loaded, not grepped: the guarantee is that the stub reads.
  (doseq [[label shape] [["a pair" '[Slug [:fn {:error/message "a \"simple\" keyword, not search"} keyword?]]]
                         ["a def form" '(def Slug [:fn {:error/message "a \"simple\" keyword, not search"} keyword?])]]]
    (testing label
      (let [s (stub/render 'gen.strings {:shapes [shape] :interfaces '[(f [x])]})]
        (load-string s)
        (is (= "a \"simple\" keyword, not search"
               (-> (resolve 'gen.strings/Slug) deref second :error/message))
            "the shape is emitted as a def, string and all")))))

(deftest a-shape-the-stub-cannot-read-is-refused-by-name
  ;; Before `start` provisions anything: the entry and its key, as data, so the refusal can
  ;; be printed with nothing to tear down.
  (let [bad (stub/check-slice '{:shapes [Concept [R [:map]] (def S [:int]) [:map [:a :int]] "x"]
                                :interfaces [(f [x])]})]
    (is (= '[[:map [:a :int]] "x"] (mapv :entry bad)))
    (is (every? #(= :shapes (:key %)) bad)))
  (is (empty? (stub/check-slice '{:shapes [] :interfaces [(f [x])]})))
  (is (thrown-with-msg? Exception #"cannot read"
                        (stub/render 'x '{:shapes [[:map [:a :int]]] :interfaces [(f [x])]}))))

(deftest a-second-implementation-file-is-refused-by-name-before-anything-is-paid
  ;; The fifth project's t-04 named handlers.clj and routes.clj; the schema admitted it, the
  ;; extraction and `sigs` passed it, two spec reviews were paid, three worktrees made - and
  ;; then write! threw. check-files is the same rule as data, for the places that run first.
  (let [[bad] (stub/check-files {:files/impl ["src/a/handlers.clj" "src/a/routes.clj"]})]
    (is (= :files/impl (:key bad)))
    (is (= ["src/a/handlers.clj" "src/a/routes.clj"] (:entry bad)))
    (is (re-find #"2 files — a task stubs exactly one implementation file" (:reason bad))))
  (is (= "empty" (subs (:reason (first (stub/check-files {:files/impl []}))) 0 5)))
  (is (empty? (stub/check-files {:files/impl ["src/a/one.clj"]})))
  (is (thrown-with-msg? Exception #"exactly one implementation file: 2 files"
                        (stub/write! (str (fs/create-temp-dir)) ["a.clj" "b.clj"] '{:interfaces [(f [x])]}))
      "and write! says the same, for a spec that reached it some other way"))

;; ---------------------------------------------------------------------------
;; writing
;; ---------------------------------------------------------------------------

(deftest writes-over-an-existing-implementation
  ;; The rewrite half: a worktree is a checkout of the whole repository, so on a
  ;; task that rewrites a namespace the previous implementation is sitting there
  ;; to be read. Writing the stub over it closes that, and greenfield gets
  ;; something to require. One mechanism, two problems.
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "src" "app"))
    (spit (str (fs/path dir "src" "app" "svc.clj")) "(ns app.svc)\n(defn f [x] (* x 2))\n")
    (is (= "src/app/svc.clj"
           (stub/write! dir ["src/app/svc.clj"] '{:shapes [[S [:map]]]
                                                  :interfaces [(f [x])]})))
    (let [now (slurp (str (fs/path dir "src" "app" "svc.clj")))]
      (is (not (str/includes? now "(* x 2)")) "the previous implementation is gone")
      (is (str/includes? now "GENERATED STUB"))
      (is (str/includes? now "(def S") "and the contract's shapes came with it"))))

(deftest one-implementation-file-per-slice
  ;; harness.contract.packet: a Blueprint slice carries the shapes and signatures for ONE
  ;; namespace, so a slice spanning two files cannot say which interface belongs
  ;; where. Throwing beats guessing.
  (let [dir (str (fs/create-temp-dir))]
    (is (thrown-with-msg? Exception #"exactly one"
                          (stub/write! dir ["a.clj" "b.clj"] '{:interfaces [(f [x])]})))))
