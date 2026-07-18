## Outcome

<!-- What observable behaviour changed, and why? -->

## Requirement and decision mapping

- Product requirements:
- Technical specification sections:
- ADRs: None

## Scope

Included:

-

Explicitly excluded or deferred:

-

## Changes

- Domain/application:
- Database/migrations:
- API/contracts:
- Observability/documentation:

## Correctness and failure evidence

- Invariants protected:
- Duplicate/concurrency behaviour:
- Failure and rollback behaviour:
- Tenant/security behaviour:

## Verification

| Command | Result |
|---|---|
| `./gradlew test` | Not run |
| `./gradlew check` | Not run |

## Checklist

- [ ] I reviewed `docs/reviews/CODE_REVIEW_CHECKLIST.md`.
- [ ] PostgreSQL-dependent behaviour uses Testcontainers, not H2.
- [ ] Critical invariants have database constraints where appropriate.
- [ ] Traceability and the active plan are updated.
- [ ] No later-release technology or unrelated refactoring was introduced.
- [ ] No secrets, real personal data, or real financial credentials are present.
- [ ] Architectural deviations are linked to an accepted ADR, or there are none.

## Remaining gaps or limitations

<!-- Write `None` only after checking. -->
