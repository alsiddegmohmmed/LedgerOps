# Contributing to LedgerOps

LedgerOps is built as a sequence of small vertical slices. Correctness and understandable evidence matter more than code volume.

## Before starting

1. Read `AGENTS.md`, both authoritative baselines, and the active Release 0.1 plan.
2. Select one bounded requirement or plan item and define its acceptance evidence.
3. Create or link an issue. Use a short branch name such as `feat/payment-idempotency` or `fix/tenant-isolation`.

If a change would alter an approved requirement, module boundary, data owner, consistency rule, or technology decision, propose an ADR and obtain approval before implementation.

## Implementation standard

- Keep changes focused; do not mix refactoring with feature work unless the refactor is required.
- Respect module and layer boundaries.
- Add tests, migrations, contracts, and documentation in the same slice as the behaviour they support.
- Never edit a released Flyway migration.
- Never include secrets, real personal data, or real financial credentials.
- Do not add technology scheduled for a later release.

## Verification and review

Run:

```bash
./gradlew test
./gradlew check
```

Use the code review checklist in `docs/reviews/CODE_REVIEW_CHECKLIST.md`. Update requirement traceability and the active plan before requesting review.

The pull request must state the mapped requirements, database effects, failure cases, exact verification evidence, remaining limitations, and any approved ADR. A passing build is necessary but does not prove financial correctness by itself.

## Commit guidance

Prefer small commits that each tell one engineering story. Use clear subjects such as:

```text
feat: enforce payment idempotency in PostgreSQL
test: prove concurrent duplicate requests create one payment
docs: map PAY-02 to concurrency evidence
```

Do not push or publish work on someone else's behalf unless they explicitly request it.
