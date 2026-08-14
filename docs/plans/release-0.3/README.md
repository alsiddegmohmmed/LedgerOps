# Release 0.3 slice plans

Follow the slices in numeric order. The active release plan is [`../release-0.3-financial-operations.md`](../release-0.3-financial-operations.md).

- Slice 0: documentation/authorization - completed.
- Slice 1: identity/audit/request context - completed.
- Slice 2: Tenant/Merchant onboarding, membership, credentials, and support - completed.
- Slice 3: Payment/Ledger operations, timeline, notes, and audit search - implemented; backend verification complete.
- Slice 4: Casework foundation and manual Risk workflow - complete for Slice 4 scope; NTF-01 notification consumption remains partial by explicit current-scope decision.
- Slice 5: Risk configuration, Provider controls, manual retry, and merchant webhooks - complete for Slice 5 scope; NTF-01 remains partial by explicit current-scope decision.
- Slice 6: Full-payment Reversal - implemented; backend/frontend automated verification, authenticated request/persistence browser coverage, asynchronous Provider completion/retry browser coverage, and the root Gradle check pass.
- Slice 7: settlement generation, upload, validation, and durable ingestion - complete; matching and posting are covered by Slices 8/9.
- Slice 8: immutable Reconciliation, current-run promotion, and settlement posting - implemented and verified in the current branch; final clean-scope/documentation release checks remain in Slice 11.
- Slice 9: controlled settlement corrections and complete Case resolution - implemented and integrated in the current branch; final clean-scope/documentation release checks remain in Slice 11.
- Slice 10: dashboard, SSE, reports, export, and Operations Web experience - implemented for approved current scope; NTF-01 notification consumer/read state and Arabic/RTL localization remain explicit deferrals.
- Slice 11: backend/frontend command, scale, dependency, topology/demo, migration, contract, and browser evidence passed; manual accessibility review is explicitly deferred and final clean-scope/documentation closure remains pending.

Use [`ORCHESTRATION.md`](ORCHESTRATION.md) for agent ownership and contradiction handling.
