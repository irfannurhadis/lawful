(ns lawful.core-test
  "Tests for lawful.core: (1) the hand-written *-laws suites, (2) test.check
   properties that regenerate the same laws over fresh inputs, (3) optimisation
   equivalences (fused-fmap, fmap-n, chain, sequence-m), and (4) stack-safety
   under large N."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [lawful.core :as l]))

;;; ---------------------------------------------------------------------------
;;; 1. Hand-written law suites
;;; ---------------------------------------------------------------------------

(deftest all-laws-green
  (doseq [[suite-name suite] (l/all-laws)]
    (testing (str suite-name)
      (doseq [[law-name ok?] suite]
        (is (true? ok?) (str suite-name " / " law-name))))))

;;; ---------------------------------------------------------------------------
;;; 2. Property-based laws (fresh inputs each run)
;;; ---------------------------------------------------------------------------

(def ^:private int-gen gen/large-integer)
(def ^:private incf  (fn [x] (l/just (inc x))))
(def ^:private dblf  (fn [x] (l/just (* 2 x))))
(defn- step-at [steps i a] ((nth steps i) a))

(deftest maybe-properties
  (testing "functor identity + composition"
    (tc/quick-check 200
      (prop/for-all [x int-gen]
        (let [m (l/just x)]
          (and (= (l/fmap m identity) m)
               (= (l/fmap (l/fmap m inc) inc)
                  (l/fmap m (comp inc inc))))))))
  (testing "monad laws"
    (tc/quick-check 200
      (prop/for-all [x int-gen]
        (and (= (l/>>= (l/just x) incf) (incf x))
             (= (l/>>= (l/just x) l/just) (l/just x))
             (= (l/>>= (l/>>= (l/just x) incf) dblf)
                (l/>>= (l/just x) (fn [a] (l/>>= (incf a) dblf)))))))))

(deftest either-properties
  (tc/quick-check 200
    (prop/for-all [x int-gen]
      (let [r (l/right x)]
        (and (= (l/fmap r identity) r)
             (= (l/>>= r (fn [a] (l/right (inc a)))) (l/right (inc x)))))))
  (testing "Left short-circuits under fmap and bind"
    (let [e (l/left :boom)]
      (is (= (l/fmap e inc) e))
      (is (= (l/>>= e (fn [a] (l/right a))) e)))))

(deftest identity-properties
  (tc/quick-check 200
    (prop/for-all [x int-gen]
      (let [m (l/id-m x)]
        (and (= (l/fmap m identity) m)
             (= (l/>>= m l/id-m) m)
             (= (l/>>= (l/id-m x) (fn [a] (l/id-m (inc a))))
                (l/id-m (inc x))))))))

(deftest reader-properties
  (tc/quick-check 200
    (prop/for-all [_x int-gen, en int-gen]
      (let [env {:n en}
            m   (l/reader (fn [e] (:n e)))
            run (fn [r] (l/run-reader r env))]
        (and (= (run (l/fmap m identity)) (run m))
             (= (run (l/>>= m (fn [a] (l/reader (fn [_] a))))) (run m)))))))

(deftest writer-properties
  (testing "laws and log-monoid associativity over generated vectors"
    (tc/quick-check 200
      (prop/for-all [x int-gen, a (gen/vector int-gen), b (gen/vector int-gen)]
        (and (= (l/run-writer (l/writer x)) [x []])
             (l/right? (l/writer* x a))                 ; vector log is valid
             (l/left?  (l/writer* x ::not-a-monoid))    ; keyword log is not
             ;; log associativity of the two generated vectors
             (= (l/mappend (l/mappend [] a) b)
                (l/mappend [] (l/mappend a b)))
             (= (l/run-writer (l/>>= (l/writer* x a) l/writer))
                (l/run-writer (l/writer* x a))))))))

(deftest state-properties
  (tc/quick-check 200
    (prop/for-all [x int-gen, s int-gen]
      (let [m (l/state (fn [st] [st st]))]
        (and (= (l/run-state (l/fmap m identity) x) (l/run-state m x))
             (= (l/run-state (l/>>= m (fn [a] (l/state (fn [st] [a st])))) s)
                (l/run-state m s)))))))

;;; ---------------------------------------------------------------------------
;;; 3. Optimisation equivalences (each justified by a law)
;;; ---------------------------------------------------------------------------

(deftest optimisation-equivalences
  (testing "fused-fmap = nested fmap (functor composition)"
    (tc/quick-check 200
      (prop/for-all [x int-gen]
        (let [m (l/just x)]
          (= (l/fused-fmap inc inc m)
             (l/fmap inc (l/fmap inc m)))))))
  (testing "fmap-n = repeated fmap"
    (tc/quick-check 200
      (prop/for-all [x int-gen]
        (let [m (l/just x)]
          (= (l/fmap-n m [inc inc inc])
             (l/fmap inc (l/fmap inc (l/fmap inc m))))))))
  (testing "chain = naive right-assoc bind (monad associativity)"
    (tc/quick-check 200
      (prop/for-all [x int-gen]
        (let [steps [(fn [a] (l/just (inc a)))
                     (fn [a] (l/just (* 2 a)))
                     (fn [a] (l/just (- a 3)))]]
          (= (l/chain (l/just x) steps)
             (l/>>= (l/just x)
                    (fn [a] (l/>>= (step-at steps 0 a)
                                   (fn [b] (step-at steps 1 b))))))))))
  (testing "sequence-m = manual traverse"
    (let [ms [(l/just 1) (l/just 2) (l/just 3)]]
      (is (= (l/sequence-m (fn [v] (l/just v)) ms)
             (l/just [1 2 3]))))
    (testing "short-circuits on Nothing"
      (is (l/nothing? (l/sequence-m (fn [v] (l/just v))
                                    [(l/just 1) l/nothing (l/just 3)]))))))

;;; ---------------------------------------------------------------------------
;;; 4. Stack safety under large N
;;; ---------------------------------------------------------------------------

(deftest stack-safety
  (let [n 100000]
    (testing "eager Maybe chain in constant stack"
      (is (= 100000 (:value (l/chain (l/just 0)
                                     (repeat n (fn [k] (l/just (inc k)))))))))
    (testing "chain-state in constant stack"
      (is (= [100000 100000]
             (l/run-state
              (l/chain-state (l/state (fn [s] [s s]))
                             (repeat n (fn [k] (l/state (fn [s] [(inc k) (inc s)])))))
              0))))
    (testing "chain-reader in constant stack"
      (is (= 100000
             (l/run-reader
              (l/chain-reader (l/reader (fn [env] env))
                              (repeat n (fn [k] (l/reader (fn [_] (inc k))))))
              0))))
    (testing "sequence-state in constant stack"
      (is (= [ [1 1 1] 3 ]
             (l/run-state (l/sequence-state
                           (repeat 3 (l/state (fn [s] [1 (inc s)]))))
                          0))))))

;;; ---------------------------------------------------------------------------
;;; 5. Totality: nothing throws on the documented total surface
;;; ---------------------------------------------------------------------------

(deftest totality
  (testing "writer* validates at construction, never throws"
    (is (l/right? (l/writer* 1 [])))
    (is (l/left?  (l/writer* 1 ::not-a-monoid))))
  (testing "mappend is total for valid monoids"
    (is (= [1 2] (l/mappend [1] [2])))
    (is (= "ab"  (l/mappend "a" "b")))
    (is (= 3     (l/mappend 1 2))))
  (testing "pure lifts into every tag"
    (doseq [tag [:maybe :either :identity :reader :writer :state]]
      (is (some? (l/pure tag 1))))))
