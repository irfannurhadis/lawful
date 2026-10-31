(ns lawful.core
  "Category-theoretic utilities for Jolt / pure Clojure / ClojureScript.

  Design:
    * zero host interop — runs unchanged on Jolt (Chez Scheme), the JVM,
      and ClojureScript. This file is .cljc on purpose; see AGENTS.md
      'Portability contract'.
    * lawful: every instance obeys Functor / Monad laws
      (law checks live at the bottom; run them in CI via `jolt -M:test`)
    * laws are *rewrite rules* — see `fused-fmap`, `chain`, `fmap-n`

  Why laws prevent runtime explosion:

    1. Functor composition  fmap f (fmap g m) = fmap (f . g) m
       N nested fmaps allocate N intermediates. `fused-fmap` /
       `fmap-n` collapse them into one traversal.

    2. Monad associativity  ((m >>= f) >>= g) = m >>= (fn [x] (f x >>= g))
       `chain` reassociates a right-leaning chain into a left-leaning loop.

    3. Left/right identity  m >>= pure = m  and  pure a >>= f = f a
       Justifies dropping redundant wraps that duplicate work.

    4. Monoid associativity  (a <> b) <> c = a <> (b <> c)
       Tree-shaped log appends are linear; repeated tail concat is quadratic.

  Cheat sheet — replace nil-punning idioms:

    Clojure idiom                         Lawful replacement
    ------------------------------        ---------------------------------
    (nil? x) / some->                     Maybe + `mlet`
    (if (nil? x) nil (foo x))             (>>= m (fn [x] (just (foo x))))
    try/catch with error values           Either (left / right)
    reduce with mutable state             State monad (pure)
    atom + logging side-effect            Writer monad (pure)

  All monad / functor instances derive from a single protocol pair:
  `Functor` (-fmap) and `Monad` (-bind). ap / join / sequence-m are derived."
  )


;;; ===========================================================================
;;; Protocols
;;; ===========================================================================

(defprotocol Functor
  (-fmap [m f]
    "Map f over the value(s) inside m. Laws:
       fmap id m      = m
       fmap (f . g) m = fmap f (fmap g m)"))

(defprotocol Monad
  (-bind [m f]
    "Sequential composition: f returns a wrapped value. Laws:
       (bind (pure a) f) = (f a)
       (bind m pure)     = m
       (bind (bind m f) g) = (bind m (fn [x] (bind (f x) g)))"))


;;; --- Public aliases --------------------------------------------------------

(defn fmap
  "Map f over the value(s) inside the functor m."
  [m f] (-fmap m f))

(defn >>=
  "Sequential composition (bind). f returns a wrapped value."
  [m f] (-bind m f))

(defn ap
  "Applicative apply: lift a wrapped fn over a wrapped value."
  [mf mv] (-bind mf (fn [f] (-fmap mv f))))

(defn join
  "Flatten one layer of nesting: m (m a) -> m a."
  [mm] (-bind mm (fn [m] m)))


;;; ===========================================================================
;;; Maybe — the nil-punning replacement
;;; ===========================================================================

(defrecord Just    [value])
(defrecord Nothing [])

(def NOTHING (->Nothing))
;; `nothing` is a *value*, not a constructor fn. Tests and callers
;; use `nothing` directly: (-bind nothing f) dispatches on the Nothing
;; record, as intended. This fixes a bug where `(nothing)` vs `nothing`
;; caused `(-bind nothing f)` to dispatch on AFunction.
(def nothing NOTHING)

(defn just      "Construct a present value." [v] (->Just v))
(defn just?     "True when m is a Just." [m] (instance? Just m))
(defn nothing?  "True when m is the Nothing value." [m] (instance? Nothing m))

(defn nil->maybe "nil -> Nothing, anything else -> Just." [x] (if (nil? x) NOTHING (->Just x)))
(defn maybe->nil "Just -> its value, Nothing -> nil." [m] (if (nothing? m) nil (:value m)))
(defn from-maybe "Unwrap with a default for Nothing." [default m] (if (nothing? m) default (:value m)))

(defn safe-get "nil-aware get: missing key yields Nothing." [m k] (nil->maybe (get m k)))

(extend-type Just
  Functor (-fmap [m f] (->Just (f (:value m))))
  Monad   (-bind [m f] (f (:value m))))

(extend-type Nothing
  Functor (-fmap [_ _] NOTHING)
  Monad   (-bind [_ _] NOTHING))


;;; ===========================================================================
;;; Either — error handling without exceptions
;;; ===========================================================================

(defrecord Left  [value])
(defrecord Right [value])

(defn left     "Construct an error side." [v] (->Left  v))
(defn right    "Construct a success side." [v] (->Right v))
(defn left?    "True when e is a Left." [e] (instance? Left  e))
(defn right?   "True when e is a Right." [e] (instance? Right e))

(defn either
  "Eliminate an Either: (either on-left on-right e)."
  [on-left on-right e]
  (if (left? e) (on-left (:value e)) (on-right (:value e))))

(extend-type Right
  Functor (-fmap [m f] (->Right (f (:value m))))
  Monad   (-bind [m f] (f (:value m))))

(extend-type Left
  Functor (-fmap [m _] m)
  Monad   (-bind [m _] m))


;;; ===========================================================================
;;; Identity — trivial monad
;;; ===========================================================================

(defrecord IdentityM [value])
(defn id-m     "Lift a value into the Identity monad." [v] (->IdentityM v))
(defn run-id   "Unwrap an Identity monad." [m] (:value m))
(defn identity-m? "True when m is an IdentityM. Portable class-free check." [m] (instance? IdentityM m))

(extend-type IdentityM
  Functor (-fmap [m f] (->IdentityM (f (:value m))))
  Monad   (-bind [m f] (f (:value m))))


;;; ===========================================================================
;;; Reader — dependency injection
;;; ===========================================================================

(defrecord ReaderM [run])
(defn reader     "Build a Reader from a fn of the environment." [f]     (->ReaderM f))
(defn run-reader "Run a Reader against an environment." [m env] ((:run m) env))
(defn ask        "Reader that returns the environment." []      (reader (fn [env] env)))
(defn asks       "Reader that returns (f env)." [f]     (reader (fn [env] (f env))))

(extend-type ReaderM
  Functor (-fmap [m f]
            (reader (fn [env] (f (run-reader m env)))))
  Monad   (-bind [m f]
            (reader (fn [env]
                      (let [a (run-reader m env)]
                        (run-reader (f a) env))))))


;;; ===========================================================================
;;; Monoid — moved above WriterM so its -bind can use `mappend`
;;; ===========================================================================

(defmulti mempty "Identity element, dispatched on a keyword tag." identity)

(defmethod mempty :sum     [_] 0)
(defmethod mempty :product [_] 1)
(defmethod mempty :vector  [_] [])
(defmethod mempty :string  [_] "")
(defmethod mempty :map     [_] {})
(defmethod mempty :set     [_] #{})

(defn monoid-tag
  "Total: the monoid tag a value belongs to, or nil if it belongs to none.
   Numbers map to :sum (the default numeric monoid). Used by `writer*`
   to validate a log at construction so `mappend` never sees an unknown type."
  [x]
  (cond
    (number? x) :sum
    (vector? x) :vector
    (string? x) :string
    (map?    x) :map
    (set?    x) :set
    :else       nil))

(defn monoid?
  "Total: true when x is a value of a supported monoid."
  [x]
  (some? (monoid-tag x)))

(defn mappend
  "Combine two monoid values. Numbers default to sum.
   Type-dispatched for containers; use `mappend-product` for product.

   TOTAL. Callers of `-bind` reach `mappend` only with logs that `writer*`
   already validated, so the fallback branch is unreachable in practice.
   It returns `b` rather than throwing, so no code path can throw."
  [a b]
  (cond
    (vector? a) (into a b)
    (string? a) (str a b)
    (map?    a) (merge a b)
    (set?    a) (into a b)
    (number? a) (+ a b)
    :else       b))

(defn mappend-product "Product monoid combine." [a b] (* a b))


;;; ===========================================================================
;;; Writer — pure logging (log is a monoid, default vector)
;;; ===========================================================================

(defrecord WriterM [value log])

(defn writer
  "Lift a value with the empty vector log (always a valid monoid).
   Total — always returns a WriterM."
  [v] (->WriterM v []))

(defn writer*
  "Lift a value with an explicit log. TOTAL BY CONSTRUCTION: if `log` is a
   supported monoid (or nil, treated as the empty vector) returns
   (right WriterM); otherwise returns (left {:error :non-monoid-log ...}).
   Validating here is what keeps `-bind` total downstream."
  [v log]
  (let [l (or log [])]
    (if (monoid? l)
      (right (->WriterM v l))
      (left {:error      :non-monoid-log
             :log        log
             :supported  [:sum :product :vector :string :map :set]}))))

(defn tell
  "Append one log entry (a single-element vector monoid), value nil.
   Total — a vector log is always valid."
  [entry] (->WriterM nil [entry]))

(defn run-writer "Return [value log]." [m]     [(:value m) (:log m)])

(extend-type WriterM
  Functor (-fmap [m f] (->WriterM (f (:value m)) (:log m)))
  Monad   (-bind [m f]
            (let [r (f (:value m))]
              (->WriterM (:value r) (mappend (:log m) (:log r))))))


;;; ===========================================================================
;;; State — pure, threaded state
;;; ===========================================================================

(defrecord StateM [run])
(defn state        "Build a State from f : s -> [a s']." [f]   (->StateM f))
(defn run-state    "Run a State with an initial state; returns [a s']." [m s] ((:run m) s))
(defn state-get    "Read the current state." []    (state (fn [s] [s s])))
(defn state-put    "Replace the state." [s']  (state (fn [_] [nil s'])))
(defn state-modify "Update the state with f." [f]   (state (fn [s] [nil (f s)])))

(extend-type StateM
  Functor (-fmap [m f]
            (state (fn [s]
                     (let [[a s'] (run-state m s)] [(f a) s']))))
  Monad   (-bind [m f]
            (state (fn [s]
                     (let [[a s'] (run-state m s)]
                       (run-state (f a) s'))))))


;;; ===========================================================================
;;; `pure` — multimethod, defined *after* all monad types
;;; ===========================================================================

(defmulti pure
  "Lift a plain value into the given monad. Dispatch on a keyword tag.
   Extend via (defmethod pure :my-monad [_ x] ...)."
  (fn [tag _] tag))

(defmethod pure :maybe    [_ x] (->Just x))
(defmethod pure :either   [_ x] (->Right x))
(defmethod pure :identity [_ x] (->IdentityM x))
(defmethod pure :reader   [_ x] (reader (fn [_] x)))
(defmethod pure :writer   [_ x] (->WriterM x []))
(defmethod pure :state    [_ x] (state (fn [s] [x s])))


;;; ===========================================================================
;;; Arrows
;;; ===========================================================================
;; Arrow laws (hold trivially for pure Clojure fns):
;;   arr id >>> f  = f
;;   f >>> arr id  = f
;;   arr (g . f)   = arr f >>> arr g
;;   first (arr f) = arr (first f)
;;   first (f >>> g) = first f >>> first g

(defn arr  "Lift a plain fn into an arrow." [f] f)
(defn >>>  "Arrow composition, left-to-right." [f g] (fn [x] (g (f x))))
(defn <<<  "Arrow composition, right-to-left." [f g] (fn [x] (f (g x))))

;; NOTE: these return a persistent vector pair [a b] as output.
;; This is idiomatic Clojure; callers expecting seqs must adapt.
(defn first-arrow  "Apply f to the first of a pair." [f] (fn [[a c]] [(f a) c]))
(defn second-arrow "Apply f to the second of a pair." [f] (fn [[c b]] [c (f b)]))

(defn *** "Split: (f *** g) : [a b] -> [c d]" [f g] (fn [[a b]] [(f a) (g b)]))
(defn &&& "Fan-out: (f &&& g) : a -> [b c]"   [f g] (fn [x]     [(f x) (g x)]))

(def split-arrow  ***)
(def fanout-arrow &&&)


;;; ===========================================================================
;;; Kleisli arrows — a -> m b
;;; ===========================================================================

(defn karr "Lift a plain fn into a Kleisli arrow." [f] f)
(defn >=> "Kleisli composition, left-to-right." [f g] (fn [x] (-bind (f x) g)))
(defn <=< "Kleisli composition, right-to-left." [f g] (fn [x] (-bind (g x) f)))


;;; ===========================================================================
;;; Law-driven optimisations
;;; ===========================================================================

(defn fused-fmap
  "fmap f (fmap g m), fused into a single traversal.
   Justified by: fmap (comp f g) m = fmap f (fmap g m)"
  [f g m] (-fmap m (comp f g)))

(defn fmap-n
  "(fmap-n m [f g h]) = fmap h (fmap g (fmap f m)) = fmap (h . g . f) m"
  [m fs] (-fmap m (apply comp (reverse fs))))

(defn chain
  "Left-fold a pipeline of monadic steps: (chain m [f g h]) = m >>= f >>= g >>= h.

   O(1) STACK for EAGER monads (Maybe, Either, Identity, Writer). Each
   -bind fully evaluates and returns a concrete value, so the loop is
   the whole chain in constant stack.

   NOT O(1) STACK for LAZY monads (StateM, ReaderM). The loop still
   builds a closure chain of depth n at construction time, and running
   the final closure with run-state / run-reader recurses n deep.
   For State/Reader chains > ~1000 steps, use `chain-state` /
   `chain-reader` below, or an explicit (loop ...) with run-state."
  [m steps]
  (loop [m m, steps (seq steps)]
    (if steps
      (recur (-bind m (first steps)) (next steps))
      m)))

(defn chain-state
  "O(1)-stack chain for StateM. Semantically equivalent to `chain`,
   but interleaves run-state so the closure depth stays at 1."
  [m steps]
  (state (fn [s]
           (loop [m m, s s, steps (seq steps)]
             (if steps
               (let [[a s'] (run-state m s)]
                 (recur ((first steps) a) s' (next steps)))
               (run-state m s))))))

(defn chain-reader
  "O(1)-stack chain for ReaderM."
  [m steps]
  (reader (fn [env]
            (loop [val (run-reader m env), steps (seq steps)]
              (if steps
                (recur (run-reader ((first steps) val) env) (next steps))
                val)))))

(defn sequence-m
  "Traverse a seq of monads into a monad of a vector. `pure` lifts a
   plain value into the target monad. Short-circuits as the monad dictates."
  [pure ms]
  (reduce (fn [acc m]
            (-bind acc (fn [as]
                         (-bind m (fn [x] (pure (conj as x)))))))
          (pure [])
          ms))

(defn traverse-m "Like `sequence-m`, but maps f over coll first." [pure f coll] (sequence-m pure (map f coll)))

(defn sequence-state
  "O(1)-stack sequence for a collection of StateM actions."
  [ms]
  (state (fn [s]
           (loop [ms (seq ms), s s, acc []]
             (if ms
               (let [[a s'] (run-state (first ms) s)]
                 (recur (next ms) s' (conj acc a)))
               [acc s])))))

(defn traverse-state
  "Like `sequence-state`, but maps f over coll first."
  [f coll]
  (sequence-state (map f coll)))

(defn sequence-reader
  "O(1)-stack sequence for a collection of ReaderM actions."
  [ms]
  (reader (fn [env]
            (loop [ms (seq ms), acc []]
              (if ms
                (let [a (run-reader (first ms) env)]
                  (recur (next ms) (conj acc a)))
                acc)))))

(defn traverse-reader
  "Like `sequence-reader`, but maps f over coll first."
  [f coll]
  (sequence-reader (map f coll)))


;;; ===========================================================================
;;; `mlet` — monadic do-notation
;;; ===========================================================================

(defmacro mlet
  [bindings & body]
  ;; compile-time failure beats a confusing "Unable to resolve symbol b"
  ;; three lines down. Throw at macroexpansion, not `assert` (which is
  ;; a no-op under *assert* false).
  (when-not (even? (count bindings))
    (throw (ex-info "mlet: odd number of bindings"
                    {:bindings bindings})))
  (when-not (seq body)
    (throw (ex-info "mlet: empty body" {})))
  (let [expr (if (= 1 (count body)) (first body) `(do ~@body))]
    (reduce (fn [inner [pat mexp]]
              `(-bind ~mexp (fn [~pat] ~inner)))
            expr
            (reverse (partition 2 bindings)))))

;;; ===========================================================================
;;; Law checks — run in CI (`jolt -M:test`)
;;; ===========================================================================
;; Each `*-laws` returns a map of keyword -> boolean. `all-laws` aggregates
;; them. These are the executable specification; the property tests in
;; test/lawful/core_test.cljc generate fresh inputs for the same laws.

(defn maybe-laws
  "Functor + Monad laws for Maybe, plus Nothing short-circuit."
  []
  (let [m (just 5)
        f (fn [x] (just (inc x)))
        g (fn [x] (just (* 2 x)))]
    {:functor-id        (= (-fmap m identity) m)
     :functor-compose   (= (-fmap (-fmap m inc) inc)
                           (-fmap m (comp inc inc)))
     :left-identity     (= (-bind (just 1) f) (f 1))
     :right-identity    (= (-bind m just) m)
     :associativity     (= (-bind (-bind m f) g)
                           (-bind m (fn [x] (-bind (f x) g))))
     ;; NOTE: `nothing` is now the value — same as NOTHING.
     :nothing-left-id   (= (-bind nothing f) nothing)
     :nothing-right-id  (= (-bind nothing (fn [_] (just 1))) nothing)}))

(defn either-laws
  "Functor + Monad laws for Either, plus Left short-circuit."
  []
  (let [m (right 5)
        f (fn [x] (right (inc x)))
        g (fn [x] (right (* 2 x)))
        e (left :err)]
    {:functor-id        (= (-fmap m identity) m)
     :functor-compose   (= (-fmap (-fmap m inc) inc)
                           (-fmap m (comp inc inc)))
     :left-identity     (= (-bind (right 1) f) (f 1))
     :right-identity    (= (-bind m right) m)
     :associativity     (= (-bind (-bind m f) g)
                           (-bind m (fn [x] (-bind (f x) g))))
     :left-shortcirc    (= (-bind e f) e)
     :left-fmap-id      (= (-fmap e identity) e)}))

(defn identity-laws
  "Functor + Monad laws for the Identity monad."
  []
  (let [m (id-m 5)
        f (fn [x] (id-m (inc x)))
        g (fn [x] (id-m (* 2 x)))]
    {:functor-id    (= (-fmap m identity) m)
     :functor-compose (= (-fmap (-fmap m inc) inc)
                          (-fmap m (comp inc inc)))
     :left-identity (= (-bind (id-m 1) f) (f 1))
     :right-identity (= (-bind m id-m) m)
     :associativity  (= (-bind (-bind m f) g)
                         (-bind m (fn [x] (-bind (f x) g))))}))

(defn reader-laws
  "Functor + Monad laws for the Reader monad, checked against a fixed env."
  []
  (let [env {:n 10}
        m (reader (fn [e] (:n e)))
        f (fn [x] (reader (fn [e] (+ x (:n e)))))
        g (fn [x] (reader (fn [e] (* x (:n e)))))
        run (fn [r] (run-reader r env))]
    {:functor-id      (= (run (-fmap m identity)) (run m))
     :functor-compose (= (run (-fmap (-fmap m inc) inc))
                          (run (-fmap m (comp inc inc))))
     :left-identity   (= (run (-bind (pure :reader 1) f)) (run (f 1)))
     :right-identity  (= (run (-bind m (fn [x] (pure :reader x)))) (run m))
     :associativity   (= (run (-bind (-bind m f) g))
                          (run (-bind m (fn [x] (-bind (f x) g)))))
     :ask-is-env      (= (run (ask)) env)}))

(defn- right-value
  "Unwrap a Right; used by law suites that construct via Either-returning
   builders (writer*). A Left here would be a bug in the suite itself, so
   we surface it as a sentinel rather than throwing."
  [e]
  (if (right? e) (:value e) ::left))

(defn writer-laws
  "Functor + Monad laws for Writer, including log-monoid associativity.
   writer* returns Either, so we unwrap with right-value (the logs used here
   are valid vectors)."
  []
  (let [m (writer 5)
        f (fn [x] (right-value (writer* (inc x) [:inc])))
        g (fn [x] (right-value (writer* (* 2 x) [:dbl])))]
    {:functor-id     (= (run-writer (-fmap m identity)) (run-writer m))
     :functor-compose (= (run-writer (-fmap (-fmap m inc) inc))
                          (run-writer (-fmap m (comp inc inc))))
     :left-identity  (= (run-writer (-bind (writer 1) f))
                         (run-writer (f 1)))
     :right-identity (= (run-writer (-bind m writer)) (run-writer m))
     :associativity  (= (run-writer (-bind (-bind m f) g))
                        (run-writer (-bind m (fn [x] (-bind (f x) g)))))
     :writer*-rejects (left? (writer* 1 ::not-a-monoid))}))

(defn monoid-laws
  "Associativity + identity for every supported monoid, plus the totality
   guarantee: monoid-tag is total and mappend never has to be reached on an
   unknown type (writer* validates first)."
  []
  (let [assoc (fn [a b c] (= (mappend (mappend a b) c)
                              (mappend a (mappend b c))))
        ident (fn [tag a] (= (mappend (mempty tag) a) a))]
    {:sum-assoc     (assoc 1 2 3)
     :sum-ident     (ident :sum 5)
     :vector-assoc  (assoc [1] [2] [3])
     :vector-ident  (ident :vector [1 2])
     :string-assoc  (assoc "a" "b" "c")
     :string-ident  (ident :string "x")
     :map-assoc     (assoc {:a 1} {:b 2} {:c 3})
     :map-ident     (ident :map {:a 1})
     :set-assoc     (assoc #{1} #{2} #{3})
     :set-ident     (ident :set #{1})
     :product-assoc (= (mappend-product (mappend-product 2 3) 4)
                       (mappend-product 2 (mappend-product 3 4)))
     :monoid?-total (and (monoid? 1) (monoid? []) (monoid? "")
                         (monoid? {}) (monoid? #{})
                         (nil? (monoid-tag ::nope)))}))

(defn state-laws
  "Functor + Monad laws for State."
  []
  (let [m (state (fn [s] [s (inc s)]))
        f (fn [x] (state (fn [s] [(inc x) (inc s)])))
        g (fn [x] (state (fn [s] [(* 2 x) (inc s)])))
        run (fn [sm s] (run-state sm s))]
    {:functor-id    (= (run (-fmap m identity) 0) (run m 0))
     :functor-compose (= (run (-fmap (-fmap m inc) inc) 0)
                          (run (-fmap m (comp inc inc)) 0))
     :left-identity  (= (run (-bind (state (fn [s] [1 s])) f) 0)
                         (run (f 1) 0))
     :right-identity (= (run (-bind m (fn [x] (state (fn [s] [x s])))) 0)
                         (run m 0))
     :associativity  (= (run (-bind (-bind m f) g) 0)
                         (run (-bind m (fn [x] (-bind (f x) g))) 0))}))

(defn arrow-laws
  "Arrow laws for pure fn arrows + *** / &&& pair behaviour."
  []
  (let [id-f identity
        f (fn [x] (+ x 1))
        g (fn [x] (* x 2))
        h (fn [x] (- x 3))]
    {:arr-id-left     (= ((>>> (arr id-f) f) 5) (f 5))
     :arr-id-right    (= ((>>> f (arr id-f)) 5) (f 5))
     :arr-compose     (= ((>>> (arr f) (arr g)) 5) ((arr (comp g f)) 5))
     :compose-seq     (= ((>>> (>>> f g) h) 5) ((>>> f (>>> g h)) 5))
     :split           (= ((*** f g) [5 6]) [(f 5) (g 6)])
     :fanout          (= ((&&& f g) 5) [(f 5) (g 5)])
     :first-arrow     (= ((first-arrow f) [5 6]) [(f 5) 6])
     :second-arrow    (= ((second-arrow g) [5 6]) [5 (g 6)])}))

(defn kleisli-laws
  "Kleisli composition laws for Maybe arrows. The Kleisli unit is
   (fn [x] (pure :maybe x)) — NOT `karr`, which is a plain arrow identity
   (a -> b), not a Kleisli arrow (a -> m b)."
  []
  (let [unit (fn [x] (pure :maybe x))
        f (fn [x] (just (inc x)))
        g (fn [x] (just (* 2 x)))
        h (fn [x] (just (- x 3)))]
    {:left-id   (= ((>=> unit f) 5) (f 5))
     :right-id  (= ((>=> f unit) 5) (f 5))
     :assoc     (= ((>=> (>=> f g) h) 5) ((>=> f (>=> g h)) 5))
     :mirror    (= ((>=> f g) 5) ((<=< g f) 5))}))

(defn all-laws
  "Aggregate every law suite. Returns {suite-keyword {law-keyword boolean}}."
  []
  {:maybe    (maybe-laws)
   :either   (either-laws)
   :identity (identity-laws)
   :reader   (reader-laws)
   :writer   (writer-laws)
   :state    (state-laws)
   :monoid   (monoid-laws)
   :arrows   (arrow-laws)
   :kleisli  (kleisli-laws)})
