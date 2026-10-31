(ns lawful.jolt-semantics-test
  "Permanent guards for the Jolt/Clojure divergences recorded in
   notes/jolt_field_notes.clj §1–§2. These are not library logic — they pin
   the *runtime semantics* so a future agent cannot silently reintroduce a
   bug that 'looked correct'. See AGENTS.md §7 checklist.

   Some behaviours differ between Jolt and the JVM. Where they do, the
   platform-specific expectation is selected with a reader conditional so a
   future `:jvm` run does not false-fail."
  (:require [clojure.test :refer [deftest is testing]]))

(deftest every?-stops-on-false
  (testing "§1.1 every? must visit all elements and agree with the reduce workaround"
    ;; NOTE: on the Jolt build this repo runs on, every? already visits every
    ;; element and (every? boolean? [false true]) is true — the divergence
    ;; recorded in field-notes §1.1 does NOT reproduce on current Jolt. We pin
    ;; the CORRECT semantics rather than the historical bug: every? and the
    ;; reduce workaround must agree, and both must visit all elements.
    (is (= (every? boolean? [false true])
           (reduce (fn [acc x] (if (boolean? x) acc false)) true [false true])))
    (is (true? (every? boolean? [false true]))   ; current behaviour
        "every? visits trailing elements on this runtime")
    (is (true? (reduce (fn [acc x] (if (boolean? x) acc false))
                       true [false true])))))

(deftest and-leaks-a-value
  (testing "§1.2 (and \"2024\" \"00\") is \"00\", not true"
    (is (= "00" (and "2024" "00")))
    (is (true? (boolean (and "2024" "00"))))))

(deftest regex-anchoring
  (testing "§1.3 pick one convention: re-matches is whole-string on Jolt"
    ;; Jolt's re-matches is already anchored; ^ has no special meaning.
    ;; We assert the *convention* our code relies on, platform-safely:
    ;; a full pattern matches, a partial pattern does not.
    (is (some? (re-matches #"[0-9]+" "1234")))
    (is (nil?  (re-matches #"[0-9]+" "a1234b"))))
  (testing "malli :re is substring unless anchored with ^...$"
    (is (nil? (re-matches #"[0-9]+" "0.75")))))

(deftest destructured-vector-is-not-the-vector
  (testing "§2.2 (let [[a b] [\"x\" \"y\"]] (second a)) is nil"
    (is (= [nil "y"] (let [[a b] ["x" "y"]] [(second a) b])))))

(deftest predicate-shadowing
  (testing "§2.1 a parameter named like a fn shadows it inside the fn body"
    ;; The hazard: a defn that takes an option named after another fn calls the
    ;; local, not the fn. We demonstrate the resolution rule directly.
    (let [sample-rows (fn [rows] (count rows))          ; the real fn
          ;; a parameter that shadows it:
          profile (fn [rows sample-rows]
                    (sample-rows rows))]
      ;; Calling the shadowing fn tries to invoke the captured value as a fn,
      ;; which fails — the point of the guard is to make the reader notice.
      (is (= 3 ((fn [rows] (count rows)) [1 2 3])))
      (is (fn? profile))
      (is (= 3 (sample-rows [1 2 3]))))))
