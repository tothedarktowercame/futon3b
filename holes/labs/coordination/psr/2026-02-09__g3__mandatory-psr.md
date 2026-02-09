# PSR: Mandatory PSR (Exemplar)

- Date: 2026-02-09
- Gate: G3 (Pattern Reference)
- Pattern: `coordination/mandatory-psr`
- Mission: `M-coordination-rewrite`

## Context

We are rewriting futon3 coordination as a six-gate pipeline (G5→G0) plus a Level 1 glacial loop (L1 observe/canon).

The key policy is evidence-driven execution: **no task proceeds to execution (G2) without an explicit Pattern Selection Record (PSR)** or an explicit Gap PSR.

## Pattern

`library/coordination/mandatory-psr.flexiarg`

## Decision

At G3, require a PSR before execution:

- If `:psr/type` is `:selection`, require `:psr/pattern-ref` and verify the pattern exists (via injected pattern set or `relations/pattern-exists?`).
- If `:psr/type` is `:gap`, allow execution to proceed while recording the gap rationale as typed evidence (PSR with `:psr/type :gap`).
- If neither is present, reject with `:g3/no-psr` and stop the pipeline.

## Alternatives Considered

- Allow “implicit PSR” inferred from task intent.
  - Rejected: weakens auditability and makes pattern selection non-attributable.
- Default to a generic pattern when missing PSR.
  - Rejected: hides gaps, defeats evidence-driven learning, and creates false positives for pattern competence.

## Outcome

Implemented as typed evidence + gate rejection:

- Code: `src/futon3/gate/pattern.clj`
- Errors: `src/futon3/gate/errors.clj` includes `:g3/no-psr` and `:g3/pattern-not-found`

## Evidence Link(s)

- Test coverage (rejections + acceptance):
  - `test/futon3/gate/pipeline_test.clj` (e.g. G3 rejects missing PSR; accepts real-library patterns)
- End-to-end traceability example in mission spec:
  - `holes/missions/M-coordination-rewrite.md` (“Mandatory PSR” trace)

