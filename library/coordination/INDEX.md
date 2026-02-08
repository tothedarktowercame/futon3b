# Coordination Patterns (Gate Pipeline)

These patterns specify the six-gate coordination pipeline (G5→G0) and its
evidence obligations. Each gate has two patterns.

## Gate Patterns

- G5 Task Specification
  - `coordination/task-shape-validation`
  - `coordination/intent-to-mission-binding`
- G4 Agent Authorization
  - `coordination/capability-gate`
  - `coordination/assignment-binding`
- G3 Pattern Reference
  - `coordination/mandatory-psr`
  - `coordination/pattern-search-protocol`
- G2 Execution
  - `coordination/bounded-execution`
  - `coordination/artifact-registration`
- G1 Validation
  - `coordination/mandatory-pur`
  - `coordination/cross-validation-protocol`
- G0 Evidence Durability
  - `coordination/session-durability-check`
  - `coordination/par-as-obligation`

## Theory Dependencies

These patterns are constrained by futon-theory invariants and protocols:
- `futon-theory/proof-path`
- `futon-theory/event-protocol`
- `futon-theory/error-hierarchy`
- `futon-theory/stop-the-line`

