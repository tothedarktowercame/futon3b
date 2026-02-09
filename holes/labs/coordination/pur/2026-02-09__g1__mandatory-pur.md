# PUR: Mandatory PUR (Exemplar)

- Date: 2026-02-09
- Gate: G1 (Validation)
- Pattern: `coordination/mandatory-pur`
- Mission: `M-coordination-rewrite`

## Context

In the coordination pipeline, G1 is the boundary where work is evaluated against the declared success criteria (and the pattern obligations implied by the PSR).

Without a Post-Use Record (PUR), the pipeline would allow “claims without checks”, breaking evidence-driven progression and degrading auditability.

## Pattern

`library/coordination/mandatory-pur.flexiarg`

## Decision

At G1, require a PUR after execution:

- If no PUR is produced, reject with `:g1/no-pur`.
- If criteria are evaluated and fail, reject with `:g1/criteria-not-met`.
- If criteria pass, emit a typed PUR record and continue to G0 (durability).

## Alternatives Considered

- Treat validation as optional and rely on downstream human review.
  - Rejected: breaks the “fast loop” invariants; validation must be part of the pipeline.
- Store validation only as untyped logs.
  - Rejected: weakens the typed boundary and makes cross-gate evidence flow non-checkable.

## Outcome

Implemented as typed evidence + gate rejection:

- Code: `src/futon3/gate/validate.clj`
- Errors: `src/futon3/gate/errors.clj` includes `:g1/no-pur` and `:g1/criteria-not-met`

## Evidence Link(s)

- Test coverage (happy path + rejection cases):
  - `test/futon3/gate/pipeline_test.clj` (PSR→PUR→PAR chain and validation rejections)
- Evidence shape boundary:
  - `src/futon3/gate/shapes.clj` (`PUR` record; `ProofPathEvent` union)

