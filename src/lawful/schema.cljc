(ns lawful.schema
  "malli bridge for lawful. Types live here; the algebra lives in `lawful.core`.
   `lawful.core` is pure (protocols + records, zero deps). This file adds malli
   schemas for the values flowing in and out, and Kleisli arrows that fail
   closed when a schema is violated.

   Portability: this file is .cljc. malli.core is itself portable, and every
   wrapper schema below is expressed as a portable predicate over records —
   no host class is named. See AGENTS.md 'Portability contract'.

   Design follows field-notes §1.3 and §6:
     * :re anchoring is malli's (^...$) — patterns here use the anchored form
     * schemas are only checked at construction / at Kleisli boundaries,
       NOT on every -bind (which would defeat the whole point)"
  (:require [malli.core :as m]
            [malli.error :as me]
            [lawful.core :as l]))

;;; ---------------------------------------------------------------------------
;;; Schemas for the monadic wrappers themselves
;;; ---------------------------------------------------------------------------

(def Just-schema        "A Just record."              [:fn l/just?])
(def Nothing-schema     "The Nothing record."         [:fn l/nothing?])
(def Maybe-schema       "Just or Nothing."            [:or Just-schema Nothing-schema])

(def Left-schema        "A Left record."              [:fn l/left?])
(def Right-schema       "A Right record."             [:fn l/right?])
(def Either-schema      "Left or Right."              [:or Left-schema Right-schema])

(def IdentityM-schema   "An IdentityM record. Portable: predicate, not a class."
  [:fn l/identity-m?])

;; These only assert the *wrapper*. Contents are the caller's schema.


;;; ---------------------------------------------------------------------------
;;; Validated constructors — schema on the wrapped value
;;; ---------------------------------------------------------------------------

(defn validated-right
  "right if x satisfies schema; otherwise left with a structured explain.
   Use this at every boundary where a total fn meets user data."
  [schema x]
  (if (m/validate schema x)
    (l/right x)
    (l/left {:error   :schema-violation
             :schema  schema
             :value   x
             :explain (me/humanize (m/explain schema x))})))

(defn validated-just
  "Maybe equivalent of validated-right. Nothing on violation, since Maybe
   has no error channel — use validated-right if you need the reason."
  [schema x]
  (if (m/validate schema x) (l/just x) l/NOTHING))


;;; ---------------------------------------------------------------------------
;;; Schema-annotated Kleisli arrows
;;; ---------------------------------------------------------------------------
;; An arrow here is [in-schema f out-schema] where f : in -> out (total).
;; The bridge lifts it into a Kleisli arrow: in -> Either Err out.
;;
;; This is the shape §6 of the field notes describes:
;;   reader    : Path -> Either Err Raw       = [:path        slurp+parse  :raw]
;;   normalise : Raw  -> Either Err Canonical = [:raw         normalise    :canonical]
;;   profile   : Canonical -> Profile         = [:canonical   profile      :profile]
;; and composition is `>=>`, which already short-circuits.

(defn schema->kleisli
  "Lift a total fn f into a Kleisli arrow in -> Either Err out, validating
   the input against in-schema and the output against out-schema. Fails
   closed with a structured Left on either violation."
  [in-schema f out-schema]
  (fn [x]
    (cond
      (not (m/validate in-schema x))
      (l/left {:error   :input-schema-violation
               :schema  in-schema
               :value   x
               :explain (me/humanize (m/explain in-schema x))})

      :else
      (let [y (f x)]
        (if (m/validate out-schema y)
          (l/right y)
          (l/left {:error   :output-schema-violation
                   :schema  out-schema
                   :value   y
                   :explain (me/humanize (m/explain out-schema y))}))))))

(defn compose-checked
  "Compose [in f out] arrows into one Kleisli arrow.
   Short-circuits on the first schema violation. Equivalent to
   (l/>=> (schema->kleisli …) (schema->kleisli …) …)."
  [& arrows]
  (reduce l/>=> l/right (map (fn [[i f o]] (schema->kleisli i f o)) arrows)))
