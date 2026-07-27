# Release 0.3 Slice 6 - Full-payment Reversal

Status: Pending  
Owner: One implementation owner  
Release: 0.3

## Outcome

One authorised full Reversal per completed Payment uses immutable attempts and ambiguity-safe Provider processing. Provider success atomically posts one exact compensation, completes Reversal, and changes Payment to REVERSED.

## Authority

PAY-06, PAY-08; PRV-02/04; LED-01/03/04; BR-03/06/07/08/13/14; ADR-016, ADR-020, ADR-021, ADR-023, ADR-025.

## Scope

- Reversal aggregate/persistence/uniqueness/API;
- request confirmation/reason/audit;
- typed Payment Attempt subject migration/backfill;
- Reversal Provider commands/results/work/mappings/webhooks/status recovery;
- authorised safe retry with `ReversalRetryApplication` unique by Tenant/Reversal/previous attempt, maximum three attempts;
- exact inverse ADR-020 Ledger API/template/replay;
- one-compensation-per-target database safeguard;
- atomic inbox/evidence/Ledger/Reversal/Payment/outbox/audit completion;
- Reversal detail/timeline/eligibility/retry UI;
- unresolved/failed Case request evidence.

Excluded:

- partial/cumulative Reversal;
- automatic Reversal resubmission;
- `REVERSAL_PAYABLE` posting;
- settlement movement.

## Critical invariants

- unique one Reversal per Payment;
- amount/currency copied from Payment;
- Payment stays COMPLETED until atomic success;
- exact posting Dr MERCHANT_PAYABLE / Cr PROVIDER_CLEARING;
- source `tenantId + REVERSAL + reversalId`;
- mandatory original ADR-020 compensation link;
- terminal replay validates complete shape;
- ambiguity never resubmits;
- no network call in transaction.

## Verification

- exhaustive transitions/terminal states;
- concurrent request/start/result/retry-application exact replay;
- attempt sequence/idempotency/hash/migration;
- Provider response/status/webhook duplicates/conflicts/out-of-order;
- all transaction failpoints and final database state;
- exact accounts/directions/amount/currency/source/compensation;
- tenant/Merchant/permission denial;
- UI confirmation/reason/evidence;
- full commands.

## Completion report

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 6
- Deviations: None
