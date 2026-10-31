(ns lawful.test-runner
  "Portable test entry point. Jolt: `jolt -M:test`. JVM/cljs seams are in
   deps.edn. Collects every lawful.* test namespace and runs clojure.test /
   cljs.test, exiting non-zero on failure so CI can gate on it."
  (:require [clojure.test :as t]
            [lawful.core-test]
            [lawful.schema-test]
            [lawful.jolt-semantics-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'lawful.core-test
                                          'lawful.schema-test
                                          'lawful.jolt-semantics-test)]
    (if (zero? (+ fail error))
      (println "lawful: all tests passed")
      (do (println "lawful: FAILURE" {:fail fail :error error})
          #?(:clj (System/exit 1)
             :cljs (js/process.exit 1))))))
