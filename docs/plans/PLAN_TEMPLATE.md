# Implementation plan: <short feature name>

Status: Draft  
Owner: <name or agent task>  
Release: 0.1 — Transactional Core  
Last updated: YYYY-MM-DD

## Outcome

State the observable result in one paragraph. Describe what will be true when this slice is complete.

## Authority and requirement mapping

- Product requirements: `<IDs and sections>`
- Technical decisions: `<sections>`
- Related ADRs: `<links or None>`

Quote only short phrases when needed; link to the authoritative documents instead of duplicating them.

## Current evidence

Describe relevant code, tests, migrations, and known gaps found during inspection.

## Scope

Included:

- <small deliverable>

Excluded:

- <explicitly deferred behaviour>

## Assumptions, risks, and dependencies

- Assumption: <what is believed and how it will be checked>
- Risk: <failure or correctness risk and mitigation>
- Dependency: <required earlier slice or external condition>

## Smallest correct sequence

1. <domain rule and focused tests>
2. <persistence/migration and integration tests>
3. <application/API/failure behaviour>
4. <full verification and documentation>

Keep steps independently reviewable. Do not assign two agents overlapping ownership of a domain contract, schema, or transaction boundary.

## Expected files

- Add: `<path>`
- Modify: `<path>`
- No change expected: `<important boundary>`

## Acceptance and verification

| Acceptance condition | Evidence | Command | Status |
|---|---|---|---|
| <observable behaviour> | <test or inspection> | `<command>` | Not run |

## Failure and recovery cases

- <invalid input or state>
- <duplicate/concurrent attempt>
- <database or downstream failure>
- <expected rollback or recovery>

## Documentation updates

- `docs/requirements/TRACEABILITY.md`
- <API, ADR, runbook, known limitation, or None>

## Completion report

- Changed: <summary>
- Verified: <commands and results>
- Incomplete: <remaining gaps or None>
- Deviations: <approved ADR or None>
