# AGENTS.md — Operational Guide for Futon3b

This file tells you what to build, in what order, and how to verify it.
The full specification is `holes/missions/M-coordination-rewrite.md` — read
it for context, but work from the phases below.

## What Exists

```
src/futon3b/query/transcript.clj   — session transcript search (JSONL files)
src/futon3b/query/relations.clj    — core.logic relations over transcripts + patterns
src/futon3/gate/task.clj           — G5 task specification gate
src/futon3/gate/auth.clj           — G4 agent authorization gate
src/futon3/gate/pattern.clj        — G3 pattern reference gate
src/futon3/gate/exec.clj           — G2 execution gate
src/futon3/gate/validate.clj       — G1 validation gate
src/futon3/gate/evidence.clj       — G0 evidence durability gate
src/futon3/gate/pipeline.clj       — G5→G0 composition
src/futon3/gate/shapes.clj         — evidence shape specs (Malli)
src/futon3/gate/errors.clj         — gate rejection catalog
src/futon3/gate/util.clj           — ID generation, timestamps
src/futon3/gate/default.clj        — C-default stub
test/futon3/gate/pipeline_test.clj — 8 tests: per-gate rejection + happy path
test/futon3b/query/relations_test.clj  — query layer tests
test/futon3b/query/transcript_test.clj — transcript search tests
deps.edn                           — core.logic, XTDB, SQLite, Malli, data.json
library/coordination/              — 12 coordination pattern flexiargs (local copies)
```

The pattern library lives at `~/code/futon3/library/` and `~/code/futon3b/library/`.
Session transcripts live at `~/.claude/projects/`.

**Note:** Gate namespaces use `futon3.gate.*` (per mission §2.1), not `futon3b.gate.*`.
The `deps.edn` has a local dep on `../futon3a` for future store composition.

## Argument Traceability

The mission identifies 7 tensions (E1-E7) in futon3's current architecture.
Each phase below says which tensions it addresses. When a phase is verified,
the corresponding tensions move from "on paper" to "in code."

| ID | Tension | Status |
|----|---------|--------|
| E1 | Pattern store disconnected from phylogeny | on paper |
| E2 | Checks don't enforce pattern selection | **scaffold** (G3 rejects missing PSR; not yet wired to real store) |
| E3 | Trails lack typed evidence | **scaffold** (6 evidence shapes defined + validated via Malli) |
| E4 | Workday input unvalidated | **scaffold** (G5 validates task shape, mission-ref, criteria) |
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

## Phase 1: Gate Scaffold [DONE]

**Built by:** Codex (commits f0daac7, 395fd67)
**Reviewed by:** Claude (this session)
**Bug fixed:** validate.clj shape-validation bypass (lines 50-54)

### What Was Built

Codex delivered the full gate scaffold: 6 gate namespaces, pipeline composition,
evidence shapes (Malli), error catalog (14 types), utility namespace, and
pipeline integration tests (8 tests, 36 assertions). Also created local copies
of 12 coordination pattern flexiargs and added query layer tests.

### Verification Results

```
clj -X:test → 10 tests, 36 assertions, 0 failures, 0 errors
```

**Acceptance checklist:**

- [x] Each gate rejects malformed input with a typed error from errors.clj
- [x] Each error carries `:gate/id` attribution (Codex used `:gate/id` not `:error/gate`)
- [x] Pipeline with valid input passes through all 6 gates, returns `{:ok true}`
- [x] Pipeline with invalid input stops at the first failing gate
- [x] Evidence shape specs validate all 6 record types (Malli, not spec.alpha — fine)
- [x] Each gate namespace docstring cites its two coordination patterns
- [x] Traceability: `:g3/no-psr` → `gate.pattern` → `coordination/mandatory-psr` → mission §2.3 E2

**Issues found and resolved:**

1. **validate.clj bug** — `shapes/validate!` result was discarded; invalid PUR
   shapes silently passed through. Fixed: shape validation now gates the flow.
   (commit by Claude)

**Known design notes (not bugs, decisions to carry forward):**

2. **Namespace is `futon3.gate.*`** — matches mission §2.1. If we decide the
   gate code permanently lives in futon3b, rename later. Not urgent.
3. **Duplicated pattern library** — `library/coordination/` has local copies of
   the 12 patterns also in `futon3/library/coordination/`. The relations.clj
   search already reads both roots. Keep futon3 as authoritative source;
   futon3b copies are convenient for tests but should not diverge.
4. **futon3a local dep** — `deps.edn` has `{:local/root "../futon3a"}`. Will
   fail without futon3a checkout. Acceptable for dev; note for CI.
5. **Tests in one file** — `pipeline_test.clj` covers all gates via the pipeline.
   Per-gate test files can be added as gates gain complexity in Phase 2.

### Tensions Addressed

- E4 → **scaffold** (G5 validates task shape, mission-ref, criteria)
- E3 → **scaffold** (6 evidence shapes defined + Malli validation at each gate)
- E2 → **scaffold** (G3 rejects tasks without PSR or with unknown pattern)

"Scaffold" = validation logic exists, doesn't yet connect to real stores.

---

## Phase 2: Store Integration + Gate Logic [CURRENT]

**Goal:** Replace injected stubs with real store calls. Gates should read from
and write to actual stores, and the pipeline should produce durable evidence.

**Depends on:** Phase 1 verified (done)

### What to Build

**1. Wire G3 to the query layer** — `src/futon3/gate/pattern.clj`

Currently G3 checks `(:patterns/ids patterns)` — a set passed in the input.
Replace with a call to `futon3b.query.relations/patterns` to search the actual
pattern library. When a PSR references a pattern-id, G3 should verify it
exists via `(relations/search pattern-id)` or a dedicated pattern-exists?
relation.

This connects Prototype 0 (search) to Phase 1 (gates) — completing the E5
tension ("search not wired to pipeline").

**2. Wire G5 to mission config** — `src/futon3/gate/task.clj`

Currently G5 checks `(get-in state [:ports :I-missions])` — a map passed in.
For now, load missions from EDN config files (e.g. `holes/missions/*.edn` or
a mission registry). Later this becomes XTDB-backed. The key change: G5 should
be able to resolve a mission-ref to a real mission document and check its state.

**3. Wire G0 to durable storage** — `src/futon3/gate/evidence.clj`

Currently G0 calls an injected `evidence/sink` function. Replace with actual
proof-path persistence — either EDN append to a proof-path log file (simple,
durable) or XTDB submit-tx (richer, queryable). Start with EDN append;
XTDB can come later.

The proof-path file should be queryable by the search layer — add a
`proof-patho` relation to `relations.clj` so proof paths are searchable
like transcripts and patterns.

**4. Wire G2 to real execution** — `src/futon3/gate/exec.clj`

This is the hardest gate to make concrete because "execution" depends on what
the task is. For Phase 2, support two execution modes:

- **Check DSL evaluation**: Wrap futon3's P2 check DSL if available. A task
  whose `:task/type` is `:check` gets evaluated against a check spec.
- **Pass-through**: For tasks that are "done" (e.g. documentation tasks,
  review tasks), the exec gate just registers the artifact as-is.

Budget enforcement (timeout via `deref` on a `future`) already works.

**5. Add proof-path query tests** — `test/futon3/gate/`

- Pipeline produces a proof-path file that can be read back
- Proof-path entries are searchable via `relations/search`
- Round-trip test: run pipeline → write proof-path → search for task-id → find it

### How to Verify

```bash
clj -X:test
```

Additional verification:
- [ ] G3 rejects unknown pattern-ids by checking the real pattern library
- [ ] G5 resolves mission-ref to a real mission config file
- [ ] G0 writes proof-path to a file that survives JVM restart
- [ ] Proof-path file is parseable EDN with all 6 gate evidence records
- [ ] Round-trip: pipeline → proof-path file → query layer → find the task
- [ ] Pipeline still works with injected stubs (backward compatible)

### What NOT to Build

- No XTDB write path yet (read-only for XTDB in this phase)
- No real futon3a composition (typed arrows, chain scoring) — that's Phase 2b
  if the basic wiring works
- No HTTP endpoint
- No Level 1

### Tensions Addressed

When this phase is verified:

- E5 → **resolved** (search wired to G3, proof-paths queryable)
- E1 → **partial** (patterns checked against real library; full phylogeny needs futon3a arrows)
- E7 → **partial** (proof-paths as queryable evidence; full graph needs XTDB write path)

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
