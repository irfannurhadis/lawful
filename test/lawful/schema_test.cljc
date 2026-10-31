(ns lawful.schema-test
  "Tests for lawful.schema: wrapper schemas (portable), validated constructors,
   and `compose-checked` Kleisli short-circuit behaviour."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [lawful.core :as l]
            [lawful.schema :as ls]))

(deftest wrapper-schemas
  (testing "Maybe"
    (is (m/validate ls/Maybe-schema (l/just 1)))
    (is (m/validate ls/Maybe-schema l/nothing))
    (is (not (m/validate ls/Maybe-schema 1))))
  (testing "Either"
    (is (m/validate ls/Either-schema (l/right 1)))
    (is (m/validate ls/Either-schema (l/left :e)))
    (is (not (m/validate ls/Either-schema 1))))
  (testing "IdentityM — portable predicate, not a host class"
    (is (m/validate ls/IdentityM-schema (l/id-m 1)))
    (is (not (m/validate ls/IdentityM-schema 42)))))

(deftest validated-constructors
  (is (l/right? (ls/validated-right :int 5)))
  (is (l/left?  (ls/validated-right :int "x")))
  (is (l/just?  (ls/validated-just :int 5)))
  (is (l/nothing? (ls/validated-just :int "x")))
  (testing "violation payload is structured"
    (let [e (ls/validated-right :int "x")]
      (is (= :schema-violation (:error (:value e))))
      (is (contains? (:value e) :explain)))))

(deftest compose-checked
  (let [inc-arrow (ls/schema->kleisli [:int] inc [:int])
        pipe      (ls/compose-checked [[:int] inc [:int]]
                                      [[:int] inc [:int]])]
    (testing "happy path composes"
      (is (= (l/right 3) (pipe 1))))
    (testing "input violation short-circuits to Left"
      (is (l/left? (pipe "x"))))
    (testing "single arrow equivalence"
      (is (= (l/right 2) (inc-arrow 1)))
      (is (= :input-schema-violation (:error (:value (inc-arrow "x"))))))))

(deftest valid-input-prop
  (tc/quick-check 200
    (prop/for-all [x gen/large-integer]
      (l/right? (ls/validated-right :int x)))))
