# AGENTS.md — read this first

`lawful` is a **pure category-theory toolkit for Jolt**. It gives you lawful
`Functor`/`Monad` machinery (Maybe, Either, Identity, Reader, Writer, State),
arrows, Kleisli composition, monoid helpers, and a malli bridge — with **zero
host interop**.

**Target runtime: Jolt (Chez Scheme) — and only Jolt is verified.** Sources are
`.cljc` as a cheap hedge, but this library makes **no promise of JVM or
ClojureScript correctness**. For those runtimes use **fluokitten** or **cats**.
Do not claim portability you have not tested.

If you are an AI (or human) extending this library, this file is the contract.
Read it before writing code. The reasoning behind it lives in
`notes/jolt_field_notes.clj` §6–§7.

---

## 1. The one rule that governs everything: TOTALITY

**No function on the public surface throws.** If a value can be absent or
invalid, the function returns a `Maybe` (`just`/`nothing`) or an `Either`
(`right`/`left`) — it does not throw.

This is enforced by construction, not by catching:

- **`writer*` validates at the boundary.** It returns `Either`: `right` with a
  `WriterM` when the log is a supported monoid, `left` with
  `{:error :non-monoid-log ...}` otherwise. Because invalid logs never become a
  `WriterM`, `WriterM -bind` is *provably total* — it only ever sees valid logs.
- **`mappend` is total.** It has no throwing branch; its fallback returns its
  second argument and is unreachable given the `writer*` gate.
- **`nil`-punning is replaced** by Maybe + `mlet`.
- **Errors are values** — `Either`, not exceptions.

### The single exception: `mlet` at macroexpansion

`mlet` throws `ex-info` at **macroexpansion** (not runtime) for an odd binding
count or an empty body. This is compile-time, produces no runtime value, and is
the idiomatic signal for a malformed special form. It is not a data-flow throw.
If you extend `mlet`, keep malformed uses failing at macroexpansion.

**When you add a function:** decide its "missing/invalid" channel (Maybe or
Either) up front. Never introduce a `throw` on the runtime path.

---

## 2. Laws are the type system we have

Clojure has no compile-time type checker here. **Laws are the substitute.** Every
instance and every optimisation is specified by a law, and every law is checked
two ways:

1. **Hand suites** — `(l/all-laws)` returns `{suite {law boolean}}`.
2. **Property tests** — `test/lawful/core_test.cljc` regenerate the same laws over
   `test.check` inputs.

Covered suites: `:maybe :either :identity :reader :writer :state :monoid
:arrows :kleisli`.

**Every optimisation is a rewrite justified by a law.** If you add one, add a
property proving it equals the unoptimised form:

| Optimisation      | Justified by                    |
|-------------------|---------------------------------|
| `fused-fmap`      | functor composition             |
| `fmap-n`          | repeated functor composition    |
| `chain`           | monad associativity             |
| `sequence-m`      | monad + traversal order         |
| `chain-state`     | State bind + O(1) stack         |
| `chain-reader`    | Reader bind + O(1) stack        |

Do not optimise "for speed" without stating the law. A rewrite with no law is a
bug waiting for a discriminating input.

---

## 3. Source format and host discipline (Jolt target)

Everything under `src/` and `test/` is **`.cljc`** — a cheap hedge, not a promise.
**Only Jolt is verified.** Do not assume the JVM or ClojureScript behaves
correctly here; use fluokitten/cats for those.

**Allowed:**
- protocols, records, `defmulti`/`defmethod`, `ex-info`
- persistent collections, lazy seqs, transducers
- `clojure.test`, `clojure.test.check`, malli (`malli.core` / `malli.error`)
- `instance?` on **our own records** (records are real types under Jolt)

**Avoid host-specific constructs** unless you are deliberately targeting Jolt:
- `java.*`, `Math/`, `js/...`, `System/…` (the test runner's `System/exit` is
  Jolt-only and fine here because Jolt is the only runtime)
- naming a record's compiled class

**Never name a record's compiled class** in a schema. Use a predicate
(`l/identity-m?`, `l/just?`, `l/left?` in `lawful.schema`). This was a real bug:
`IdentityM-schema` once said `(instance? lawful.IdentityM %)` and failed to
resolve. Fixed by `[:fn l/identity-m?]`.

---

## 4. Layers: total vs fallible (see field-notes §6)

```
reader      : Path -> Either Error Raw        (fallible — I/O)
normalise   : Raw  -> Either Error Canonical  (fallible — parsing)
profile     : Canonical -> Profile            (TOTAL)
output      : Profile x Options -> Renderable (TOTAL)
serialise   : Renderable -> String            (TOTAL)
```

The **two fallible layers touch the filesystem**; the three pure layers are total.
`lawful.schema/schema->kleisli` + `compose-checked` express this boundary: each
arrow validates its input and output schema and fails closed with a structured
`Left`. Pure computation is never wrapped in a throw.

If you add an I/O boundary, model it as a `schema->kleisli` arrow. Keep the pure
numeric/data code total.

---

## 5. Running the tests (exact commands)

Primary (Jolt):

```bash
jolt -M:test
```

This loads `lawful.test-runner/-main`, which runs every `lawful.*` test namespace
and exits non-zero on failure. `test.check` is declared explicitly in the `:test`
alias — do not rely on it arriving transitively via malli.

From a live nREPL (Jolt):

```clojure
(require '[clojure.test :as t]
         '[lawful.core-test] '[lawful.schema-test] '[lawful.jolt-semantics-test]
         :reload-all)
(t/run-tests 'lawful.core-test 'lawful.schema-test 'lawful.jolt-semantics-test)
```

JVM and cljs runners are **not provided** — Jolt is the only supported runtime.
For the JVM or ClojureScript, use fluokitten/cats. The `.cljc` extension is a
hedge, not a tested portability claim.

> **Critical (field-notes §3.2):** deps.edn is resolved at REPL **startup**.
> After changing deps or adding a file that changes the classpath, **restart the
> nREPL and reconnect**. `:reload` is not enough — it can keep loading a stale or
> deleted `.clj` file.

---

## 6. The §7 checklist, as runnable guidance

Before writing code, run the matching guard from
`test/lawful/jolt_semantics_test.cljc` and probe in the REPL:

- **Before a predicate** (§1.1, §1.2): predicates return real booleans. Do not
  chain `and` into a predicate. Prefer malli or an explicit `reduce`.
  *Probe:* `(every? pred xs)` on data that can be `false`.
- **Before a regex** (§1.3): pick **one** anchoring convention.
  `re-matches` is whole-string on Jolt; malli `:re` is substring unless `^...$`.
  Never mix them.
- **Before maths** (§1.4): do not hand-roll; the right answer is a correctly
  rounded library (out of scope for this zero-dep lib — put it in the consumer).
- **Before a helper whose type matters** (§1.5): no primitive return hint if the
  value type must be preserved.
- **Before a dependency** (§3.1, §3.2): git coordinate, then restart the REPL.
- **Before believing a `defn`** (§2.1, §2.2): call it with a discriminating value.
  Beware a local parameter shadowing the fn (`sample-rows` calling itself) and
  re-deriving a destructured pair from its first element.
- **Before stopping** (§5): is the next fact in the workspace? If not, ask.

---

## 7. What "done" means for a change

1. The new var has a docstring stating its law/contract.
2. It has a hand law (if it's an instance/optimisation) **and** a `test.check`
   property over generated inputs.
3. It names no host class and stays within the Jolt-verified subset (see §3).
4. It is total on its runtime path (no `throw`; use Maybe/Either).
5. `jolt -M:test` is green.
6. If it changes behaviour another agent could rely on, update `docs/API.md`.
