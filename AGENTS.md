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
test/futon3/gate/pipeline_test.clj — 17 tests: gate rejection + happy path + store integration
test/futon3b/query/relations_test.clj  — query layer tests
test/futon3b/query/transcript_test.clj — transcript search tests
data/missions.edn                  — mission registry (loaded by G5)
data/proof-paths/                  — durable proof-path EDN files (written by G0)
deps.edn                           — core.logic, XTDB, SQLite, Malli, data.json
library/coordination/              — 12 coordination pattern flexiargs (local copies)
holes/labs/coordination/psr/       — PSR exemplars (process artifacts)
holes/labs/coordination/pur/       — PUR exemplars (process artifacts)
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
| E1 | Pattern store disconnected from phylogeny | **resolved** (G3 checks real library; L1 canonizer writes new patterns from evidence) |
| E2 | Checks don't enforce pattern selection | **resolved** (G3 validates PSR pattern-ref against real pattern library) |
| E3 | Trails lack typed evidence | **resolved** (6 evidence shapes + durable proof-path EDN files, queryable) |
| E4 | Workday input unvalidated | **resolved** (G5 validates task shape + resolves mission-ref from registry) |
| E5 | Search not wired to pipeline | **resolved** (G3 uses query layer; proof paths searchable via relations) |
| E6 | Greenfield prototypes stalled | **resolved** (L1 glacial loop canonizes new patterns from accumulated evidence) |
| E7 | Graph convergence unexploited | **resolved** (proof paths queryable; L1 loop closes evidence→library feedback) |

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

## Phase 2: Store Integration + Gate Logic [DONE]

**Goal:** Replace injected stubs with real store calls. Gates should read from
and write to actual stores, and the pipeline should produce durable evidence.

**Depends on:** Phase 1 verified (done)

**Built by:** Claude (two commits: f3b6a1f for G3+G0, then G5+G2+tests)

### What Was Built

**1. G3 wired to the query layer** — `src/futon3/gate/pattern.clj`

`patterns-exists?` now has a fallback chain: injected `:patterns/exists?` fn →
injected `:patterns/ids` set → `relations/pattern-exists?` (real library scan).
When no injected config is provided, G3 checks the actual pattern library at
`~/code/futon3b/library/` and `~/code/futon3/library/`.

**2. G5 wired to mission config** — `src/futon3/gate/task.clj`

G5 falls back to `relations/load-missions` when no `:I-missions` map is injected.
Missions are loaded from `data/missions.edn` — a simple EDN registry mapping
mission-ref → `{:mission/state :active ...}`.

**3. G0 wired to durable storage** — `src/futon3/gate/evidence.clj`

G0 falls back to `relations/append-proof-path!` when no injected sink is provided.
Each proof-path is written as an individual EDN file in `data/proof-paths/`,
named by `path/id`. Files contain the full proof-path + evidence map + timestamp.

**4. G2 passthrough mode** — `src/futon3/gate/exec.clj`

Refactored `register-artifact` into a shared helper. Two execution modes:
- **Injected exec/fn** — called with task context, budget-enforced via `promise`/`deref`
- **Pass-through** — if no exec/fn but an `:artifact` map is in the request,
  register it directly. For documentation, review, or pre-completed tasks.

**5. Proof-path store + query** — `src/futon3b/query/relations.clj`

Added: `append-proof-path!`, `load-proof-paths`, `search-proof-paths`,
`proof-patho` (core.logic relation), `pattern-exists?`, `pattern-ids`,
`load-missions`, `missions-file`, `proof-path-dir`.

**6. Mission registry** — `data/missions.edn`

EDN file with `M-coordination-rewrite` as `:active`. Loaded by G5 as fallback.

### Verification Results

```
17 tests, 54 assertions, 0 failures, 0 errors
```

**Acceptance checklist:**

- [x] G3 rejects unknown pattern-ids by checking the real pattern library
- [x] G3 accepts known patterns (e.g. `coordination/mandatory-psr`) from real library
- [x] G5 resolves mission-ref from `data/missions.edn` when no I-missions injected
- [x] G5 rejects unknown mission-ref from registry with `:g5/mission-not-active`
- [x] G0 writes proof-path EDN file that survives JVM restart
- [x] Proof-path file is parseable EDN with all 6 gate evidence records
- [x] Round-trip: pipeline → proof-path file → `search-proof-paths` → find the task
- [x] G2 accepts passthrough artifact without exec/fn
- [x] Pipeline still works with injected stubs (all 8 original tests pass)
- [x] Backward compatible: all Phase 1 tests unchanged and passing

**New tests added (9 integration tests):**

| Test | What it checks |
|------|---------------|
| `g3-resolves-real-pattern` | G3 finds `coordination/mandatory-psr` in real library |
| `g3-rejects-nonexistent-real-pattern` | G3 rejects fake pattern without injected set |
| `durable-sink-writes-proof-path` | Pipeline writes EDN file without injected sink |
| `proof-path-round-trip-queryable` | Write → search by task-id → find it |
| `g5-resolves-mission-from-registry` | G5 loads from `data/missions.edn` |
| `g5-rejects-unknown-mission-from-registry` | G5 rejects nonexistent mission |
| `g2-passthrough-artifact` | G2 accepts passthrough without exec/fn |

Test isolation: `with-temp-proof-dir` fixture creates a temp directory per test
and `with-redefs` redirects `relations/proof-path-dir` to avoid polluting real data.

### Tensions Addressed

- E5 → **resolved** (search wired to G3, proof-paths queryable via `search-proof-paths` + `proof-patho`)
- E2 → **resolved** (G3 validates PSR pattern-ref against real pattern library)
- E3 → **resolved** (6 evidence shapes + durable proof-path EDN files, queryable)
- E4 → **resolved** (G5 validates task shape + resolves mission-ref from registry)
- E1 → **partial** (patterns checked against real library; full phylogeny needs futon3a arrows)
- E7 → **partial** (proof-paths as queryable evidence; full graph needs XTDB write path)

---

## Phase 3: Level 1 — Library Evolution [DONE]

**Goal:** Tension observer + canonicalizer. The glacial loop from mission
Part III. Level 1 watches Level 0 proof-paths for recurring tensions and
evolves the pattern library.

**Built by:** Claude
**Tensions addressed:** E6 (resolved), completes E1 + E7

**Depends on:** Phase 2 verified (done)

### What Was Built

**1. Evidence shapes + error types** — `src/futon3/gate/shapes.clj`

Added `TensionObservation` and `CanonizationEvent` Malli shapes. Extended
`ProofPathEvent` gate/id enum to include `:l1-observe` and `:l1-canon`.
Added 4 Level 1 errors to `errors.clj`: `:l1/no-proof-paths`,
`:l1/no-tensions-found`, `:l1/canon-no-candidate`, `:l1/write-failed`.

**2. Tension observer** — `src/futon3/gate/observe.clj` (NEW)

Three scan functions implementing the criteria from
`futon-theory/structural-tension-as-observation`:

- `scan-structural-irritation` — recurring gap-PSRs clustered by fingerprint
- `scan-pre-symbolic-pressure` — repeated early-gate (G5/G3) rejections
- `scan-trans-situational` — same pattern used across 2+ missions

**3. Canonicalizer** — `src/futon3/gate/canon.clj` (NEW)

Three-step Baldwin cycle from `futon-theory/retroactive-canonicalization`:

- `name-tension` — generates candidate pattern-id from fingerprint
- `select-candidate` — filters by threshold (frequency≥3, contexts≥2, evidence≥2)
- `canalize!` — writes new `.flexiarg` to library, invalidates cache

**4. Level 1 composition** — `src/futon3/gate/level1.clj` (NEW)

Composes L1-observe → L1-canon, analogous to `pipeline.clj` for G5→G0.
Short-circuits if no tensions found or no candidates meet threshold.

**5. Concrete diagram updated** — `futon5/data/missions/futon3-coordination.edn`

Added I-tensions input port, O-canonizations output port, L1-observe and
L1-canon components, and 5 Level 1 edges including the glacial loop close
(L1-canon → I-patterns).

### Verification Results

```
27 tests, 88 assertions, 0 failures, 0 errors
ct/mission.clj: 8/8 checks pass
```

**New tests added (10 Level 1 tests):**

| Test | What it checks |
|------|---------------|
| `observer-finds-structural-irritation` | 3 gap-PSR proof-paths → structural-irritation tension |
| `observer-finds-pre-symbolic-pressure` | 3 proof-paths with G5 rejections → pre-symbolic-pressure tension |
| `observer-finds-trans-situational` | Same pattern across 2 missions → trans-situational tension |
| `observer-returns-empty-on-no-tensions` | 1 clean proof-path → `{:ok true :tensions []}` |
| `observer-rejects-empty-store` | No proof-paths → `:l1/no-proof-paths` |
| `canonicalizer-names-tension` | Tension → CanonizationEvent with `:naming` phase |
| `canonicalizer-selects-by-threshold` | Below-threshold filtered out; above-threshold selected |
| `canonicalizer-writes-flexiarg` | Above-threshold → new `.flexiarg` file on disk |
| `full-loop-round-trip` | **Integration**: gap-PSRs via pipeline → L1 observe → canonize → G3 accepts new pattern |
| `shapes-validate` | TensionObservation and CanonizationEvent pass Malli validation |

**Acceptance checklist:**

- [x] Tension observer produces structured observations from accumulated evidence
- [x] Canonicalizer produces canonization events that update the library
- [x] Full loop test: gap-PSR accumulation → tension observation → canonization → library update → G3 accepts new pattern
- [x] I3 check: all Level 1 edges are glacial→glacial (timescale-ordering passes)
- [x] I4 check: no fast output→I-patterns path (exogeneity passes)
- [x] `ct/mission.clj` validates the complete two-loop diagram: 8/8 checks pass

### Tensions Addressed

- E6 → **resolved** (L1 glacial loop canonizes new patterns from accumulated evidence)
- E1 → **resolved** (G3 checks real library; L1 canonizer writes new patterns from evidence)
- E7 → **resolved** (proof paths queryable; L1 loop closes evidence→library feedback)

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
