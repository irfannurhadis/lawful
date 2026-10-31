# lawful API reference

Two namespaces:

- **`lawful.core`** — the algebra. No dependencies. Pure. Total on its runtime
  path (nothing throws; absence/invalidity is `Maybe`/`Either`).
- **`lawful.schema`** — the malli bridge. Adds schemas and schema-validated
  Kleisli arrows that fail closed.

All sources are `.cljc`; **Jolt is the only verified runtime**. For the JVM or
ClojureScript, use fluokitten/cats instead. See `AGENTS.md`.

---

## `lawful.core`

### Protocols and derived combinators

| Var | Signature | Notes |
|---|---|---|
| `Functor` / `-fmap` | `(-fmap m f)` | Laws: `fmap id = id`, `fmap (f∘g) = fmap f ∘ fmap g`. |
| `Monad` / `-bind` | `(-bind m f)` | Laws: left id, right id, associativity. |
| `fmap` | `(fmap m f)` | Public alias for `-fmap`. |
| `>>=` | `(>>= m f)` | Public alias for `-bind`. |
| `ap` | `(ap mf mv)` | Derived: apply a wrapped fn. |
| `join` | `(join mm)` | Derived: flatten one layer. |

### Maybe — nil-punning replacement

| Var | Signature | Notes |
|---|---|---|
| `Just` / `->Just` | record | Present value. |
| `Nothing` / `->Nothing` | record | Absent value. |
| `NOTHING` / `nothing` | value | The single `Nothing` instance. `nothing` is a **value**, not a constructor. |
| `just` | `(just v)` | Construct a Just. |
| `just?` | `(just? m)` | Predicate. |
| `nothing?` | `(nothing? m)` | Predicate. |
| `nil->maybe` | `(nil->maybe x)` | `nil` → Nothing, else Just. |
| `maybe->nil` | `(maybe->nil m)` | Just → value, Nothing → nil. |
| `from-maybe` | `(from-maybe default m)` | Unwrap with default. |
| `safe-get` | `(safe-get m k)` | `nil`-aware get → Maybe. |

### Either — errors as values

| Var | Signature | Notes |
|---|---|---|
| `Left` / `Right` | records | Error / success. |
| `left` / `right` | `(left v)` `(right v)` | Constructors. |
| `left?` / `right?` | predicates | `-fmap`/`-bind` short-circuit on `Left`. |
| `either` | `(either on-left on-right e)` | Eliminate. |

### Identity

| Var | Signature | Notes |
|---|---|---|
| `IdentityM` / `id-m` | `(id-m v)` | Trivial monad. |
| `run-id` | `(run-id m)` | Unwrap. |
| `identity-m?` | `(identity-m? m)` | Portable predicate (used by schema). |

### Reader — dependency injection

| Var | Signature | Notes |
|---|---|---|
| `reader` | `(reader f)` | `f : env -> a`. |
| `run-reader` | `(run-reader m env)` | Run against an env. |
| `ask` | `(ask)` | Return the env. |
| `asks` | `(asks f)` | Return `(f env)`. |

### Monoid

| Var | Signature | Notes |
|---|---|---|
| `mempty` | `(mempty tag)` | Identity per tag: `:sum :product :vector :string :map :set`. |
| `monoid-tag` | `(monoid-tag x)` | **Total**: tag of `x`, or `nil`. |
| `monoid?` | `(monoid? x)` | **Total**: `x` is a supported monoid. |
| `mappend` | `(mappend a b)` | **Total**: combine. Fallback returns `b` (unreachable via `writer*`). |
| `mappend-product` | `(mappend-product a b)` | Product combine. |

### Writer — pure logging

| Var | Signature | Notes |
|---|---|---|
| `WriterM` | record `{:value :log}` | |
| `writer` | `(writer v)` | Empty vector log. **Total.** |
| `writer*` | `(writer* v log)` | **Returns `Either`**: `right` WriterM if `log` is a monoid, `left {:error :non-monoid-log}` otherwise. |
| `tell` | `(tell entry)` | Append one entry (single-vector log). **Total.** |
| `run-writer` | `(run-writer m)` | `[value log]`. |

`WriterM -bind` is **total by construction**: it only ever combines logs that
`writer*`/`writer`/`tell` already validated.

### State — pure threaded state

| Var | Signature | Notes |
|---|---|---|
| `state` | `(state f)` | `f : s -> [a s']`. |
| `run-state` | `(run-state m s)` | Returns `[a s']`. |
| `state-get` | `(state-get)` | Read state. |
| `state-put` | `(state-put s')` | Replace state. |
| `state-modify` | `(state-modify f)` | Update state. |

### `pure` — lift into a monad by tag

`(pure tag x)` for `tag ∈ :maybe :either :identity :reader :writer :state`.
Extend with `(defmethod pure :my-monad [_ x] ...)`.

### Arrows (pure fns)

| Var | Signature | Notes |
|---|---|---|
| `arr` | `(arr f)` | Lift a plain fn. |
| `>>>` / `<<<` | compose | Left-to-right / right-to-left. |
| `first-arrow` / `second-arrow` | | Apply to first / second of a pair. |
| `***` | `(*** f g)` | `[a b] -> [c d]`. |
| `&&&` | `(&&& f g)` | `a -> [b c]` fan-out. |
| `split-arrow` / `fanout-arrow` | aliases for `***` / `&&&` | |

Arrow laws: identity, composition, split/fanout behaviour. (Checked in
`:arrows` suite.)

### Kleisli (`a -> m b`)

| Var | Signature | Notes |
|---|---|---|
| `karr` | `(karr f)` | Plain-fn arrow identity (`a -> b`). **Not** the Kleisli unit. |
| `>=>` | `(>=> f g)` | Kleisli composition, left-to-right. |
| `<=<` | `(<=< f g)` | Right-to-left. |

The **Kleisli unit** is `(fn [x] (pure tag x))`, not `karr`. `karr` only makes
sense where a plain `b` is acceptable.

### Law-driven optimisations

| Var | Signature | Equals | Law |
|---|---|---|---|
| `fused-fmap` | `(fused-fmap f g m)` | `(fmap f (fmap g m))` | functor composition |
| `fmap-n` | `(fmap-n m [f g h])` | nested `fmap`, one traversal | functor composition |
| `chain` | `(chain m steps)` | `m >>= f >>= g …` | monad associativity |
| `chain-state` | `(chain-state m steps)` | `chain` on StateM, O(1) stack | State + stack safety |
| `chain-reader` | `(chain-reader m steps)` | `chain` on ReaderM, O(1) stack | Reader + stack safety |
| `sequence-m` | `(sequence-m pure ms)` | manual traverse, short-circuits | monad + order |
| `traverse-m` | `(traverse-m pure f coll)` | `sequence-m` after `map` | |
| `sequence-state` / `traverse-state` | | O(1) stack | |
| `sequence-reader` / `traverse-reader` | | O(1) stack | |

**Stack note:** `chain` is O(1) stack for **eager** monads (Maybe, Either,
Identity, Writer) but **not** for lazy `StateM`/`ReaderM` — use
`chain-state`/`chain-reader` there. This is stated in the docstrings and
verified under N=100000 in `core_test.cljc`.

### `mlet` — monadic do-notation

```clojure
(mlet [a (just 1)
       b (just 2)]
  (just (+ a b)))
```

Expands to nested `-bind`. Malformed uses (odd bindings, empty body) fail at
macroexpansion.

### Law suites

`maybe-laws`, `either-laws`, `identity-laws`, `reader-laws`, `writer-laws`,
`state-laws`, `monoid-laws`, `arrow-laws`, `kleisli-laws`, and
`(all-laws)` which aggregates all nine. Each returns `{law boolean}`.

---

## `lawful.schema`

| Var | Signature | Notes |
|---|---|---|
| `Just-schema`, `Nothing-schema`, `Maybe-schema` | malli schemas | Wrapper only; contents are caller's schema. |
| `Left-schema`, `Right-schema`, `Either-schema` | malli schemas | |
| `IdentityM-schema` | `[:fn l/identity-m?]` | **Portable** — no host class. |
| `validated-right` | `(validated-right schema x)` | `right x` or `left {:error :schema-violation :schema :value :explain}`. |
| `validated-just` | `(validated-just schema x)` | `just x` or `nothing`. |
| `schema->kleisli` | `(schema->kleisli in f out)` | Lift a total `f` into `in -> Either Err out`, validating both ends, failing closed with `:input-schema-violation` / `:output-schema-violation`. |
| `compose-checked` | `(compose-checked & arrows)` | Compose `[in f out]` arrows with `l/>=>`; short-circuits on first violation. |

### Totality boundary (see AGENTS.md §4)

`schema->kleisli` + `compose-checked` are the seam between fallible I/O and
total computation: each arrow validates its input and output, so a schema
violation becomes a structured `Left` rather than a thrown exception. Pure
layers stay total.
