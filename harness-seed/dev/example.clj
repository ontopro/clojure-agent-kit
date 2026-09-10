(ns example
  "`bb example` — the whole loop shape in one run, with no model calls, no
  network and no sibling checkout. Read it top to bottom; it is the
  shortest description of what the other namespaces are for."
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pp]
   [harness.gates :as gates]
   [harness.packet :as packet]
   [harness.repair :as repair]
   [harness.runner :as runner]
   [harness.runner-check :as check]
   [harness.shapes :as shapes]))

(def spec
  "One entry of an Architect's dependency-ordered task list."
  {:task/id "t-07-service-ops"
   :task/title "Service ops: lookup, children, search"
   :blueprint/slice {:shapes '[Concept] :interfaces '[(lookup [store id])]}
   :files/impl ["src/app/service.clj"]
   :files/test ["test/app/service_test.clj"]
   ;; note the impl file is listed here on purpose
   :files/context ["src/app/store.clj" "src/app/service.clj"]
   :layer/name :service})

(defn- scripted-runner
  "Stands in for a real agent: writes the file it was asked for — with a
  deliberately missing trailing newline, so gate 0 has something to do."
  [dir]
  (reify runner/AgentRunner
    (run-agent [_ role packet]
      (if (= :reviewer role)
        {:status :done :files [] :stdout "approve" :cost nil
         :runner/meta {:verdict "approve"}}
        (let [f (first (:files/target packet))]
          (fs/create-dirs (fs/path dir (fs/parent f)))
          (spit (str (fs/path dir f)) "(ns app.service)\n\n(defn lookup [store id])")
          {:status :done :files [f] :stdout nil :cost 0.12
           :runner/meta {:session-id "demo-session"}})))))

(defn -main [& _]
  (let [dir (str (fs/create-temp-dir))
        session {:worktree/path dir :nrepl/port 7807}
        runner (scripted-runner dir)]

    (println "\n1. Assemble the packets — one spec, two roles.\n")
    (let [coder (packet/coder-packet spec session)
          tester (packet/tester-packet spec session)]
      (println "   Coder sees: " (:files/context coder))
      (println "   Tester sees:" (:files/context tester))
      (println "   ^ the impl was stripped for the Tester. It reads the contract,")
      (println "     never the code — enforced in harness.packet, not asked for.\n")

      (println "2. Dispatch. A real runner shells a CLI or calls an endpoint;")
      (println "   this one just writes the file.\n")
      (let [result (runner/run-agent runner :coder coder)]
        (println "   =>" (pr-str (dissoc result :stdout)))
        (println "   valid AgentResult?" (shapes/valid-result? result))
        (println "   ^ :session-id lives under :runner/meta, where the schema")
        (println "     can see it. Upstream it rode on the top level, undeclared.\n")

        (println "3. Gate 0 — mechanical repair, before anything is judged.\n")
        (let [r (repair/repair! dir (:files result))]
          (println "   repaired:" (:repaired r) " newline added:" (:newlines r))
          (println "   ^ no retry budget was spent on that.\n"))

        (println "4. The gates, cheap first, short-circuiting.\n")
        (let [gr (gates/run-gates! dir [[:fmt "true"] [:lint "false"] [:test "true"]])]
          (pp/pprint (:gates/report gr))
          (println "\n   failed:" (:gates/failed gr) "— :test never ran.")
          (println "   Triage gets {which gate, its output}, not a composite log.\n")))

      (println "5. Conformance — run every runner you write through this.\n")
      (let [results (check/check-runner runner {:packet coder :expect-writes? true})]
        (doseq [{:keys [check pass?]} results]
          (println "  " (if pass? "ok  " "FAIL") (name check)))
        (println "\n  " (if (check/conforms? results) "conforms" "DOES NOT CONFORM"))))

    (println "\nWorktree:" dir "\n")))
