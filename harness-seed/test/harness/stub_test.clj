(ns harness.stub-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [harness.stub :as stub]))

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
  ;; Run 5: `(is (thrown? Exception (render e)))` is an ordinary case for a
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
  (testing "and an interface with no arg vector names itself"
    (is (thrown-with-msg? Exception #"no argument vector"
                          (stub/render 'x '{:interfaces [(broken)]})))))

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
  ;; Bare symbols NAME a shape defined elsewhere — the form packet/example-packet
  ;; uses. There is nothing to emit, and emitting a guess would be a fabricated
  ;; contract, which is exactly what :label-fabricated forbids.
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
  (is (str/starts-with? (stub/shape-def '[R [:map]]) "(def R")))

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
  ;; harness.packet: a Blueprint slice carries the shapes and signatures for ONE
  ;; namespace, so a slice spanning two files cannot say which interface belongs
  ;; where. Throwing beats guessing.
  (let [dir (str (fs/create-temp-dir))]
    (is (thrown-with-msg? Exception #"exactly one"
                          (stub/write! dir ["a.clj" "b.clj"] '{:interfaces [(f [x])]})))))
