# AGENTS.md — Operational Guide for Futon3b

This file tells you what to build, in what order, and how to verify it.
The full specification is `holes/missions/M-coordination-rewrite.md` — read
it for context, but work from the phases below.

## What Exists

```
src/futon3b/query/transcript.clj   — session transcript search (JSONL files)
src/futon3b/query/relations.clj    — core.logic relations over transcripts + patterns
deps.edn                           — core.logic, XTDB, SQLite, data.json
```

These implement **Prototype 0** (unified search). They work:
- `(relations/search "PlanetMath")` returns hits from transcripts + pattern library
- `(relations/session-count)` → ~820 sessions
- `(relations/pattern-count)` → ~690 patterns

The pattern library lives at `~/code/futon3/library/` and `~/code/futon3b/library/`.
Session transcripts live at `~/.claude/projects/`.

## Argument Traceability

The mission identifies 7 tensions (E1-E7) in futon3's current architecture.
Each phase below says which tensions it addresses. When a phase is verified,
the corresponding tensions move from "on paper" to "in code."

| ID | Tension | Status |
|----|---------|--------|
| E1 | Pattern store disconnected from phylogeny | on paper |
| E2 | Checks don't enforce pattern selection | on paper |
| E3 | Trails lack typed evidence | on paper |
| E4 | Workday input unvalidated | on paper |
| E5 | Search not wired to pipeline | **partial** (Prototype 0 search works, not yet wired to gates) |
| E6 | Greenfield prototypes stalled | on paper |
| E7 | Graph convergence unexploited | on paper |

Update this table as phases complete.

---

## Phase 0: Prototype 0 — Unified Search [DONE]

**What was built:** Federated search across session transcripts and pattern
library via core.logic relations.

**Files:** `src/futon3b/query/transcript.clj`, `src/futon3b/query/relations.clj`

**Verification (all pass):**
- [x] `(relations/search "PlanetMath")` returns results from both stores
- [x] `(relations/session-count)` returns > 0
- [x] `(relations/pattern-count)` returns > 0
- [x] Pattern cache invalidates (5-second TTL)
- [x] No file descriptor leaks (with-open + eager realization)

**Tensions addressed:** E5 (partial — search works, pipeline wiring is Phase 2)

---

## Phase 1: Gate Scaffold [CURRENT]

**Goal:** Create the namespace structure for all six gates, the evidence shape
specs, and the error catalog. Every gate should exist as a stub that validates
its input shape and rejects malformed input with a typed error. The pipeline
should compose all six gates in order.

This is the "all gates rejecting malformed input" half of the Prototype 0 exit
condition in the mission doc (§2.6 items 1-4).

### What to Build

**1. Evidence shapes** — `src/futon3b/gate/shapes.clj`

Define Clojure specs (or schema maps) for the six evidence record types from
mission §1.2:

```
:task-spec   {:task/id, :task/mission-ref, :task/scope, :task/typed-io, :task/success-criteria, :task/intent}
:assignment  {:assign/agent-id, :assign/task-id, :assign/capabilities, :assign/exclusive?}
:psr         {:psr/id, :psr/task-id, :psr/pattern-ref, :psr/candidates, :psr/rationale, :psr/type}
:artifact    {:artifact/id, :artifact/task-id, :artifact/type, :artifact/ref, :artifact/registered-at}
:pur         {:pur/id, :pur/psr-ref, :pur/criteria-eval, :pur/outcome, :pur/prediction-error}
:par         {:par/id, :par/session-ref, :par/what-worked, :par/what-didnt, :par/prediction-errors, :par/suggestions}
```

Use `clojure.spec.alpha` for validation. Each shape should have a `valid?`
function and a `explain` function that returns a human-readable error.

**2. Error catalog** — `src/futon3b/gate/errors.clj`

Define the gate rejection types from mission §2.2. Each error is a map:

```clojure
{:error/gate   :g5          ;; which gate rejected
 :error/key    :g5/missing-mission-ref
 :error/message "Task has no mission reference"
 :error/data   {}}          ;; context for debugging
```

Provide a constructor: `(gate-error :g5 :g5/missing-mission-ref {:task task})`.

The full error key list is in mission §2.2 (14 error types across 6 gates).

**3. Gate stubs** — one namespace per gate:

```
src/futon3b/gate/task.clj       ;; G5 — validates task shape
src/futon3b/gate/auth.clj       ;; G4 — validates agent registration
src/futon3b/gate/pattern.clj    ;; G3 — validates PSR exists
src/futon3b/gate/exec.clj       ;; G2 — stub (execution logic comes in Phase 2)
src/futon3b/gate/validate.clj   ;; G1 — validates PUR exists
src/futon3b/gate/evidence.clj   ;; G0 — validates evidence persistence
```

Each gate namespace must:
- Have a docstring citing its two coordination patterns (from mission §2.1)
- Export a single function: `(pass ctx)` → returns updated ctx or throws/returns gate error
- Validate its input against the relevant evidence shape
- Return a typed error (from errors.clj) on validation failure

The `ctx` map flows through the pipeline. Each gate reads what it needs from
ctx and assoc's its output evidence record back into ctx.

**4. Pipeline composition** — `src/futon3b/gate/pipeline.clj`

Thread a request through G5 → G4 → G3 → G2 → G1 → G0. Stop at the first
gate that returns an error. Return either the completed ctx (with all evidence
records) or the error (with gate attribution).

```clojure
(defn run-pipeline
  "Run request through all six gates. Returns {:ok ctx} or {:error error}."
  [request]
  ...)
```

**5. Tests** — `test/futon3b/gate/`

One test file per gate plus one pipeline integration test:

```
test/futon3b/gate/shapes_test.clj      ;; evidence shapes validate/reject correctly
test/futon3b/gate/task_test.clj        ;; G5 rejects missing mission-ref, missing criteria
test/futon3b/gate/auth_test.clj        ;; G4 rejects unregistered agent
test/futon3b/gate/pattern_test.clj     ;; G3 rejects missing PSR
test/futon3b/gate/validate_test.clj    ;; G1 rejects missing PUR
test/futon3b/gate/evidence_test.clj    ;; G0 rejects unpersisted evidence
test/futon3b/gate/pipeline_test.clj    ;; full G5→G0 valid path; first-fail short-circuit
```

Use `clojure.test`. No external dependencies needed — gates validate shapes,
so tests just pass maps in and check what comes out.

### How to Verify

Run:
```bash
clj -X:test
```

All tests must pass. Specifically verify:

- [ ] Each gate rejects malformed input with a typed error from errors.clj
- [ ] Each error carries `:error/gate` attribution
- [ ] Pipeline with valid input passes through all 6 gates and returns `:ok`
- [ ] Pipeline with invalid input stops at the first failing gate
- [ ] Evidence shape specs validate all 6 record types
- [ ] Each gate namespace docstring cites its two coordination patterns

### What NOT to Build

- No real XTDB/SQLite/filesystem calls. Gates validate shapes only. Store
  integration comes in Phase 2.
- No HTTP endpoints. REPL-first.
- No Level 1 (tension observer, canonicalizer). That's Phase 3.
- Do not modify the existing query/ namespaces.

### Tensions Addressed

When this phase is verified, these tensions move from "on paper" to "scaffold":

- E4 (unvalidated input) → G5 validates task shape
- E3 (untyped trails) → evidence shapes defined for all inter-gate records
- E2 (no mandatory PSR) → G3 rejects tasks without PSR

"Scaffold" means the validation logic exists but doesn't yet connect to real
stores or real execution. Full resolution requires Phase 2.

### Acceptance Checklist

After Codex delivers, we verify by:

1. `clj -X:test` — all tests green
2. Read each gate namespace — docstring cites correct patterns?
3. Read pipeline_test.clj — does it exercise both valid and invalid paths?
4. Read shapes.clj — do the specs match mission §1.2 exactly?
5. Read errors.clj — do the error keys match mission §2.2 exactly?
6. Traceability spot-check: pick one error (e.g. `:g3/no-psr`), trace it
   back: error → gate.pattern → coordination/mandatory-psr.flexiarg →
   mission §2.3 E2 → ARGUMENT.flexiarg E2

If any check fails, we note what's wrong and hand it back with specific
corrections rather than vague feedback.

---

## Phase 2: Store Integration + Gate Logic [PLANNED]

**Goal:** Connect gates to real stores. G5 checks missions in XTDB. G3 uses
the query layer (Prototype 0) for pattern search. G2 writes artifacts to XTDB.
G0 persists proof paths.

**Tensions addressed:** E1, E5, E7 (full resolution)

**Depends on:** Phase 1 verified

**Details:** To be specified when Phase 1 is verified.

---

## Phase 3: Level 1 — Library Evolution [PLANNED]

**Goal:** Tension observer + canonicalizer. The glacial loop from mission
Part III.

**Tensions addressed:** Completes the two-level AIF diagram

**Depends on:** Phase 2 verified

**Details:** To be specified when Phase 2 is verified.

---

## How to Update This File

When a phase completes:

1. Move it from [CURRENT] to [DONE]
2. Fill in the verification results (what passed, what needed fixing)
3. Update the tension traceability table at the top
4. Expand the next [PLANNED] phase into a full [CURRENT] specification
5. Commit the updated AGENTS.md

This way the file accumulates a record of what was built, what was verified,
and which parts of the argument are now grounded in code vs still on paper.
