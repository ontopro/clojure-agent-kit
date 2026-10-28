(ns xtdb-probe
  "`bb health`'s XTDB check: with no argument, an in-process node on this JVM - two puts of one
  document and a read of it now and over valid time; with `--pgwire <port>`, the same over a
  server reached through its Postgres wire protocol on localhost. Prints one line, `ok ...`,
  and exits 0, or says what failed and exits 1."
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn])
  (:import [java.sql DriverManager]))

(defn- embedded []
  (with-open [node (xtn/start-node {})]
    (xt/execute-tx node [[:put-docs :docs {:xt/id 1 :v "first"}]])
    (xt/execute-tx node [[:put-docs :docs {:xt/id 1 :v "second"}]])
    (let [now (xt/q node "SELECT v FROM docs WHERE _id = 1")
          history (xt/q node "SELECT v FROM docs FOR VALID_TIME ALL WHERE _id = 1 ORDER BY _valid_from")]
      (when-not (and (= [{:v "second"}] now) (= ["first" "second"] (mapv :v history)))
        (throw (ex-info (str "read back " (pr-str now) " and " (pr-str history)) {})))
      "embedded node: two puts, read now and over valid time")))

(defn- pgwire [port]
  (with-open [c (DriverManager/getConnection (str "jdbc:postgresql://127.0.0.1:" port "/xtdb") "xtdb" "")
              s (.createStatement c)]
    (.execute s "INSERT INTO docs (_id, v) VALUES (1, 'first')")
    (.execute s "INSERT INTO docs (_id, v) VALUES (1, 'second')")
    (let [rs (.executeQuery s "SELECT v FROM docs FOR VALID_TIME ALL WHERE _id = 1 ORDER BY _valid_from")
          vs (loop [acc []] (if (.next rs) (recur (conj acc (.getString rs "v"))) acc))]
      (when-not (= ["first" "second"] vs)
        (throw (ex-info (str "read back " (pr-str vs)) {})))
      "server over pgwire: two inserts, read over valid time")))

(defn -main [& [flag port]]
  (let [t0 (System/currentTimeMillis)]
    (try
      (let [what (if (= "--pgwire" flag) (pgwire port) (embedded))]
        (println (str "ok " what (if (= "--pgwire" flag) " - probe java " " - java ")
                      (System/getProperty "java.version") ", "
                      (- (System/currentTimeMillis) t0) " ms"))
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable e
        (println (str "FAILED: " (.getName (class e)) ": " (ex-message e)))
        (shutdown-agents)
        (System/exit 1)))))
