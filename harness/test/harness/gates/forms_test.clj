(ns harness.gates.forms-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [harness.gates.forms :as forms]))

(def plain
  "(ns app.view\n  (:require [clojure.string :as str]))\n\n(def title \"t\")\n\n(defn render-page\n  [x]\n  (str/upper-case x))\n\n(defn- helper [x] x)\n\n(defmethod render :a [_] 1)\n(defmethod render :b [_] 2)\n\n(deftest ^:slow t\n  (is (= 1 1)))\n")

;; ---------------------------------------------------------------------------
;; the table
;; ---------------------------------------------------------------------------

(deftest a-plain-file-is-a-table-of-its-forms
  (let [t (forms/table plain)]
    (is (= ["ns" "def" "defn" "defn-" "defmethod" "defmethod" "deftest"] (mapv :kind t)))
    (is (= ["app.view" "title" "render-page" "helper" "render :a" "render :b" "t"] (mapv :name t))
        "a defmethod is named with its dispatch value, so two methods are two rows")
    (is (= [6 8] ((juxt :from :to) (nth t 2))) "the line range of a multi-line form")
    (is (every? integer? (map :hash t)))))

(deftest a-cljc-reader-conditional-a-splice-a-discard-and-a-bare-value
  (let [t (forms/table (str "(ns a)\n"
                            "#_(def gone 1)\n"
                            "#?(:clj (def y 2) :cljs (def z 3))\n"
                            "#?@(:clj [(def s1 1) (def s2 2)])\n"
                            "\"stray\"\n"
                            "(comment (def c 1))\n"
                            "(defn f [x] #(+ % x) @x 'q #'f #inst \"2020-01-01T00:00:00Z\" ::k ::str/k `(do ~x))\n"))]
    (is (= ["a" "y" "s1" "s2" nil nil "f"] (mapv :name t))
        "the :clj branch, the splice flattened, the discard gone, the value and the comment unnamed")
    (is (= ["ns" "def" "def" "def" "value" "comment" "defn"] (mapv :kind t)))
    (testing "a bare value has no position; the reader gives none"
      (is (nil? (:from (nth t 4)))))))

(deftest a-file-that-does-not-parse-has-no-table
  ;; The sigs rule: nil and silent. Gate 0 has already named the bracket.
  (is (nil? (forms/table "(defn f [] 1")))
  (is (nil? (forms/table "(defn f [] 1))")))
  (is (= [] (forms/table "")) "an empty file is an empty table, not a failure"))

;; ---------------------------------------------------------------------------
;; the delta, and its one line
;; ---------------------------------------------------------------------------

(deftest the-delta-names-what-was-lost-gained-and-changed
  (let [before (forms/table plain)
        after (forms/table (str "(ns app.view\n  (:require [clojure.string :as str]))\n\n"
                                "(def title \"t\")\n\n"
                                "(defn render [x] (str/upper-case x))\n\n"
                                "(defn- helper [x] (inc x))\n\n"
                                "(defmethod render :a [_] 1)\n(defmethod render :b [_] 2)\n\n"
                                "(deftest ^:slow t\n  (is (= 1 1)))\n"))]
    (is (= {:before 7 :after 7 :lost ["render-page"] :gained ["render"] :changed ["helper"]}
           (forms/delta before after)))
    (is (nil? (forms/delta before nil)) "no table, no delta")))

(deftest reformatting-is-not-a-change
  ;; Gate 0 reindents what a role wrote; the hash is of the form, not the bytes,
  ;; so the report after a write does not call every form changed.
  (is (= "forms 7 -> 7, no form changed"
         (forms/summary plain (-> plain
                                  (.replace "(defn render-page\n  [x]\n  (str/upper-case x))"
                                            "(defn render-page [x]\n      (str/upper-case x))"))))))

(deftest the-summary-is-one-line-or-nothing
  (is (= "forms 7 -> 6, lost: render-page"
         (forms/summary plain (.replace plain "(defn render-page\n  [x]\n  (str/upper-case x))\n\n" ""))))
  (is (= "forms 7 -> 8, gained: g; changed: helper"
         (forms/summary plain (.replace plain "(defn- helper [x] x)" "(defn- helper [x] (dec x))\n(defn g [])"))))
  (is (nil? (forms/summary plain "(defn f [] 1")) "the new file does not parse: silent")
  (is (nil? (forms/summary "(defn f [] 1" plain)) "the old file did not parse: silent"))
