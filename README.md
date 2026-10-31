# lawful

A small, **pure, zero-JVM-interop** category-theory toolkit for
[Jolt](https://github.com/jolt-lang). Laws are the type system: every instance
and every optimisation is checked by a law, by hand *and* with `test.check`.

> **Jolt only.** Sources are `.cljc`, but only Jolt is tested.
> For the JVM or ClojureScript, use **fluokitten** or **cats**, which
> are more mature and widely-used in their ecosystems.

## What you get

- `Functor` / `Monad` protocols and lawful instances for **Maybe, Either,
  Identity, Reader, Writer, State**
- **Arrows** (`arr`, `>>>`, `***`, `&&&`) and **Kleisli** (`>=>`, `<=<`)
- **Monoid** helpers (`mempty`, `mappend`, `monoid?`)
- **Law-driven optimisations**: `fused-fmap`, `fmap-n`, `chain`, `sequence-m`,
  plus O(1)-stack `chain-state` / `chain-reader` / `sequence-*`
- `mlet` monadic do-notation
- A **malli bridge** (`lawful.schema`): wrapper schemas and schema-validated
  Kleisli arrows that fail closed with a structured `Left`

`lawful.core` depends on **nothing**. Only `lawful.schema` needs malli — so you
can `(require '[lawful.core])` with no transitive deps at all.

## Totality

**Nothing on the public runtime path throws.** Absence is `Maybe`, failure is
`Either`. This is enforced by construction: `writer*` validates its log at
construction and returns `Either`, so `WriterM -bind` can never see an invalid
log. The single exception is `mlet`, which fails at *macroexpansion* for a
malformed binding list (compile time, not a runtime data-flow throw).

## Install

As a git dependency (recommended once tagged):

```clojure
;; deps.edn
{:deps {lawful/lawful {:git/url "https://github.com/irfannurhadis/lawful"
                       :git/tag "v0.1.0"
                       :git/sha "<sha>"}}}
```

Or locally, while developing against a checkout:

```clojure
{:deps {lawful/lawful {:local/root "/path/to/lawful"}}}
```

> Use the **qualified** lib name `lawful/lawful`. Newer Jolt warns
> `DEPRECATED: Libs must be qualified` if you write a bare `lawful`.

## Quick taste

```clojure
(require '[lawful.core :as l])

(l/>>= (l/just 1) (fn [x] (l/just (inc x))))        ;=> #lawful.core.Just{:value 2}

(l/mlet [a (l/just 1)
         b (l/just 2)]
  (l/just (+ a b)))                                  ;=> #lawful.core.Just{:value 3}

(l/right? (l/right 1))                               ;=> true
(l/left?  (l/>>= (l/left :boom) (fn [_] (l/right 1)))) ;=> true (short-circuits)
```

## Tests

```bash
jolt -M:test
```

Runs every `lawful.*` test namespace (hand laws + `test.check` properties +
Jolt-semantics guards) and exits non-zero on failure.

## Documentation

- **`AGENTS.md`** — the contract for extending this library (totality rule, laws
  as the type system, Jolt discipline, what "done" means). Read this first.
- **`docs/API.md`** — public vars grouped by monad, the laws each satisfies, and
  the optimisation→law table.
- **`notes/jolt_field_notes.clj`** — the literate field notes behind the design
  decisions, including Jolt behaviour that is version-dependent.

## Supported dependency floor

- **malli** `0.16.4` (for `lawful.schema` only). `lawful.core` needs no deps.
- Tested on `jolt v0.8.23`.


## AI Usage Disclaimer

AI has been leveraged starting 2026.10.31.
This library is targeted towards agentic usage.
