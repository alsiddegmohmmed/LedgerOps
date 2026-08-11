# Release 0.3 slice plans

Follow the slices in numeric order. The active release plan is [`../release-0.3-financial-operations.md`](../release-0.3-financial-operations.md).

- Slice 0: documentation/authorization - completed.
- Slice 1: identity/audit/request context - completed.
- Slice 2: Tenant/Merchant onboarding, membership, credentials, and support - completed.
- Slice 3: Payment/Ledger operations, timeline, notes, and audit search - implemented; backend verification complete.
- Slice 4: Casework foundation and manual Risk workflow - complete for Slice 4 scope; NTF-01 notification consumption remains partial until Slice 10.
- Slice 5: Risk configuration, Provider controls, manual retry, and merchant webhooks - complete for Slice 5 scope; NTF-01 remains partial until Slice 10.
- Slice 6: Full-payment Reversal - implemented; backend/frontend automated verification, authenticated request/persistence browser coverage, and the root Gradle check pass; asynchronous Provider completion/retry browser coverage remains outstanding.
- Slice 7: settlement generation, upload, validation, and durable ingestion - complete; matching and posting remain Slice 8/9 behavior.
- Slices 8-10: pending functional vertical slices.
- Slice 11: pending release verification gate.

Use [`ORCHESTRATION.md`](ORCHESTRATION.md) for agent ownership and contradiction handling.
