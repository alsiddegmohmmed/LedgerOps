# Release 0.3 orchestration

## Authority

Codex reads, in order:

1. Product Definition v1.7.
2. Technical Specification v1.7.
3. Accepted ADR-022 through ADR-027 and earlier accepted ADRs.
4. `release-0.3-financial-operations.md`.
5. The active slice file.
6. Repository evidence.

A lower source cannot override a higher source.

## Execution model

One implementation owner controls the active slice and is the only agent allowed to change shared domain contracts, migrations, module declarations, event schemas, or transaction boundaries.

Parallel agents may:

- inspect code and documentation;
- design tests that do not edit shared contracts;
- review security, concurrency, SQL, module boundaries, API compatibility, or UI accessibility;
- report findings to the implementation owner.

Parallel agents must not implement competing versions or edit the same schema/aggregate.

## Required task contract

Before coding, the owner reports:

- authoritative requirements/ADRs;
- exact scope and exclusions;
- current repository evidence;
- files expected to change;
- assumptions/risks/dependencies;
- smallest four-step sequence;
- verification plan.

After coding, the owner reports:

- changed behavior/files;
- exact commands/results;
- incomplete work;
- deviations (`None` unless approved ADR);
- final requirement evidence.

## Contradiction gate

Stop only when repository evidence reveals:

- a Product/Technical/accepted ADR conflict;
- a required change to module/data ownership, consistency boundary, security model, release order, accounting template, or stable identity;
- an implementation impossibility not already handled by the approved documents.

Ordinary class/table/API design consistent with authority is decided by the implementation owner and does not require product-owner interruption.

## Slice discipline

- Work only on the active numbered slice.
- Run focused tests while iterating, then full gate commands.
- Backend behavior/tests before corresponding UI.
- Never edit released Flyway migrations.
- No direct cross-module table access.
- No H2.
- No external call in a database transaction.
- No push/merge/PR outside explicit user instruction.

## Review priority

1. Financial/authorization correctness.
2. Concurrency/idempotency/transaction boundaries.
3. Tenant/Merchant isolation.
4. Failure/recovery and database constraints.
5. Module architecture.
6. API/event compatibility.
7. Observability/documentation.
8. Style/optional improvements.
