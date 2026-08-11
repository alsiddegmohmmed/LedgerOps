# Release 0.3 API and contract plan

## Contract rules

- OpenAPI supersedes the unauthenticated v0.1 sandbox examples with authenticated Release 0.3 contracts.
- RFC 7807 problems retain correlation, effect, retryable, and next-action fields.
- JSON Schema contracts remain versioned and backward-compatibility tested.
- No JPA/domain entity is an external or Kafka contract.
- Human APIs use explicit Tenant route context.
- Service Payment API derives Tenant/Merchant from credential.

## Human API groups

```text
/api/v1/platform/tenants/...
/api/v1/tenants/{tenantId}/memberships/...
/api/v1/tenants/{tenantId}/merchants/...
/api/v1/tenants/{tenantId}/credentials/...
/api/v1/tenants/{tenantId}/payments/...
/api/v1/tenants/{tenantId}/risk/...
/api/v1/tenants/{tenantId}/provider/...
/api/v1/tenants/{tenantId}/reversals/...
/api/v1/tenants/{tenantId}/settlement-batches/...
/api/v1/tenants/{tenantId}/reconciliation-runs/...
/api/v1/tenants/{tenantId}/cases/...
/api/v1/tenants/{tenantId}/ledger/...
/api/v1/tenants/{tenantId}/audit/...
/api/v1/tenants/{tenantId}/reports/...
/api/v1/tenants/{tenantId}/notifications/...
```

The exact resources are introduced only by their slice.

The first published credential API contract is
[`release-0.3-credential-actions.yaml`](release-0.3-credential-actions.yaml).
It covers non-secret metadata reads, keyset-paginated collection, and the
create, rotate, and revoke actions. Further projections and search filters
remain separate contracts introduced only when their read models are
implemented.

Slice 6 extends the authenticated Payment operational-detail response at
`GET /api/v1/tenants/{tenantId}/payments/{paymentId}` with an optional
`reversal` object. The object is a read-only lifecycle snapshot containing the
Reversal ID, originating Payment ID, Merchant, full amount/currency, status,
request actor/reason/time, processing/failure/completion times, failure
category, and version. It contains no Provider secret or other secret
material. The field is `null` when the Payment has no Reversal.

The approved Reversal actions are:

```text
POST /api/v1/tenants/{tenantId}/reversals
POST /api/v1/tenants/{tenantId}/reversals/{reversalId}/retry
```

The request action requires the completed Payment ID, `confirmation: true`,
and a non-blank reason. The retry action requires the originating Payment ID,
the previous Reversal Attempt ID, the durable Provider evidence ID,
`confirmation: true`, and a non-blank reason. The backend rechecks Tenant and
Merchant scope, current lifecycle state, and the ADR-023 safe-retry evidence;
the Operations Web does not make those decisions from presentation state.

The Operations Web shows the Reversal lifecycle/timeline evidence on the
Payment detail page. It displays a retry form only when the latest durable
Reversal evidence is `SAFE_TO_RESUBMIT`, proves no Provider acceptance, and
does not find a Provider transaction. Support sessions remain read-only.

Slice 5 contracts are documented in
[Release 0.3 Slice 5 Provider and Merchant webhook contracts](release-0.3-slice-5-provider-and-webhook-contracts.md).

Slice 7 publishes the authenticated settlement-ingestion boundary:

```text
POST /api/v1/tenants/{tenantId}/settlement-batches
GET  /api/v1/tenants/{tenantId}/settlement-batches
GET  /api/v1/tenants/{tenantId}/settlement-batches/{batchVersionId}
GET  /api/v1/tenants/{tenantId}/settlement-batches/{batchVersionId}/validation-items
POST /api/v1/tenants/{tenantId}/settlement-batches/{batchVersionId}/validate
POST /api/v1/tenants/{tenantId}/settlement-batches/{batchVersionId}/process
```

The upload is multipart form data containing the exact Provider settlement CSV,
the Provider batch reference, and the declared settlement-period dates. The
Core service hashes and stores the raw bytes before inserting batch metadata;
the public response never exposes the internal object-storage key. Upload is
authorized by Tenant-wide `settlement:upload`; batch reads and validation use
`reconciliation:read`; processing requires `reconciliation:run` and
`confirmation: true`. Validation must complete before processing is accepted.
Slice 7 stops after normalized immutable occurrences and canonical record
versions: matching, current-run promotion, discrepancies, and Ledger posting
remain Slice 8/9 behavior.

## Slice 8 reconciliation and settlement-posting contract

Slice 8 publishes the authenticated Reconciliation run, evidence, current-run,
status-history, and controlled settlement-posting boundaries. All routes are
Tenant-scoped. Read routes require Tenant-wide `reconciliation:read`.
Mutation routes require Tenant-wide `reconciliation:run` or
`reconciliation:promote` as listed below. An out-of-scope Tenant or resource is
returned as unavailable; the response does not disclose whether another
Tenant's resource exists.

### Run and evidence routes

```text
POST /api/v1/tenants/{tenantId}/reconciliation-runs
GET  /api/v1/tenants/{tenantId}/reconciliation-runs
GET  /api/v1/tenants/{tenantId}/reconciliation-runs/current?batchFamilyId={uuid}
GET  /api/v1/tenants/{tenantId}/reconciliation-runs/{runId}
GET  /api/v1/tenants/{tenantId}/reconciliation-runs/{runId}/results
GET  /api/v1/tenants/{tenantId}/reconciliation-runs/{runId}/postings
GET  /api/v1/tenants/{tenantId}/reconciliation-runs/subjects/{subjectType}/{subjectId}/status-history
```

The run request is JSON with the required fields `batchVersionId`,
`rulesVersion`, `sourceCutoff`, and `confirmation: true`. `rulesVersion` is a
printable 1–64 character value. `sourceCutoff` is an ISO-8601 instant. The
selected settlement batch must be `COMPLETED` or
`COMPLETED_WITH_DISCREPANCIES`. The response is `201` and contains the
terminal `ReconciliationRunSnapshot`, including the immutable snapshot ID,
run number, rules version, source cutoff, status, counts, timestamps, and
failure reason when present.

The run captures immutable settlement occurrences and immutable Payment and
Reversal financial facts through published module APIs. The Payment query
uses accepted final Provider results at or before `sourceCutoff`; it does not
use mutable Payment or Reversal status as the financial evidence. Provider and
Ledger evidence is then copied into the immutable snapshot. A run does not
hold one cross-module transaction while it reads those bounded pages.

The run collection accepts optional `batchFamilyId` and `limit` query
parameters. `limit` defaults to `25` and must be between `1` and `100`. Result
reads accept optional `status` (`MATCHED` or `DISCREPANCY`), `category`,
`limit` (default `50`, range `1–100`), and non-negative `offset`. Posting reads
accept `limit` and non-negative `offset` with the same default and range.
Result and posting read pages are ordered deterministically by creation time
and stable identifiers. The current-run route returns the one promoted run
for the batch family or `404` when no current run exists. Status history is
append-only and accepts only `PAYMENT` or `REVERSAL` subject types.

The result response exposes `providerValuesJson` and `internalValuesJson` as
safe evidence projections. It never exposes bearer tokens, client secrets,
invitation token hashes, or other secret material.

### Promotion and posting actions

```text
POST /api/v1/tenants/{tenantId}/reconciliation-runs/{runId}/promote
POST /api/v1/tenants/{tenantId}/reconciliation-runs/{runId}/postings/prepare
POST /api/v1/tenants/{tenantId}/reconciliation-runs/{runId}/postings
```

Each request contains `batchFamilyId`, `confirmation: true`, and a non-blank
`reason` of at most 512 characters. Promotion requires
`reconciliation:promote`. Posting preparation and application require
`reconciliation:run`. The selected run must be terminal and eligible; the
backend rechecks the Tenant, batch family, current pointer, immutable match,
and posting state. The Operations Web does not make those decisions from
presentation state. Support sessions remain read-only.

Promotion returns the `CurrentReconciliationRunSnapshot`. Preparation returns
stable posting outcomes for exact matches and creates one immutable
`SettlementPostingInstruction` plus one `PENDING` application per eligible
identity. Application returns `POSTED`, `REPLAYED`, or
`WAITING_FOR_PAYMENT` outcomes. A duplicate request reuses the existing
instruction/application and cannot create a second Ledger transaction.

Payment settlement uses:

```text
DEBIT  SETTLEMENT_RECEIVABLE
CREDIT PROVIDER_CLEARING
```

Reversal settlement uses the inverse:

```text
DEBIT  PROVIDER_CLEARING
CREDIT SETTLEMENT_RECEIVABLE
```

These are settlement postings. They are distinct from the existing Payment
and Reversal source postings used as immutable evidence. A Reversal settlement
is applied only after the exact Payment settlement application is `POSTED`;
otherwise the result is retained as
`REVERSAL_WITHOUT_PAYMENT_SETTLEMENT` and no Ledger effect is created.

### Failure behavior and lifecycle events

Malformed UUIDs, invalid enum filters, invalid limits, missing confirmation,
invalid timestamps, and invalid request fields return `400` with an
RFC 7807-style `invalid-reconciliation-request` problem. Missing or
out-of-scope resources return `404`. Missing Tenant-wide authority returns
`403`. Promotion, posting, and immutable-state races return `409` with a
retryable reconciliation-state-conflict problem. No incompatible financial
state is committed on a rejected action.

Reconciliation emits `ReconciliationLifecycleChanged` version `1` to
`ledgerops.reconciliation.lifecycle.v1` through the transactional outbox.
Run and current-run events use `batchFamilyId` as the partition key. Posting
events use `settlementPostingId`. Deduplication keys are stable per run/event,
current-run/family/run, or posting/event. Discrepancies emit
`CreateCaseRequested` through the Casework command topic. Slice 8 does not
implement the Notification consumer; notification creation and read state
remain Slice 10 behavior.

The first published Tenant configuration boundary is the authenticated
`/api/v1/tenants/{tenantId}/configuration` resource. `GET` returns the current
append-only configuration version and returns `404` before the first version
exists. `PUT` appends a new version and requires Tenant-wide
`tenant:configure` authority. The request contains one or more ISO 4217
`allowedCurrencies`, a BCP 47 `defaultLocale`, an IANA `timezone`, and a
JSON-object `displaySettings`, plus `confirmation: true` and a non-blank
`reason`. The response includes the generated version and creation timestamp.
The reason is preserved in immutable audit evidence. Both operations require
the selected Tenant to match the route and never expose authorization
internals or bearer tokens.

Operational contacts remain a separate contract and are not implied by this
resource.

The first Merchant administration read boundary is tenant-scoped:

```text
GET /api/v1/tenants/{tenantId}/merchants
GET /api/v1/tenants/{tenantId}/merchants/{merchantId}
```

The collection is ordered by normalized Merchant name and Merchant ID and is
filtered by the caller's effective Merchant scope. The response exposes only
`tenantId`, `merchantId`, `name`, `status`, and persistence `version`. Reads
require human `merchant:read` authority; an out-of-scope Merchant is reported
as unavailable rather than disclosed. Merchant lifecycle mutation remains a
separate action contract and is not implied by these reads.

The first Membership administration read boundary is tenant-scoped:

```text
GET /api/v1/tenants/{tenantId}/memberships
GET /api/v1/tenants/{tenantId}/memberships/{membershipId}
```

Reads require a human `tenant:membership-manage` authority. Tenant-wide callers
may see all memberships in the selected Tenant. Merchant-scoped callers see
only memberships with a Merchant-scoped role assignment intersecting their
effective Merchant set; a Tenant-wide-only membership is not disclosed to
them. The response includes membership status, identity-link state, role and
scope assignments, and a safe invitation summary with intended email, status,
and expiry. It never includes the invitation token hash or other secret
material. Membership and invitation mutation remain separate action contracts.

The first Membership mutation is invitation revocation:

```text
POST /api/v1/tenants/{tenantId}/memberships/{membershipId}/invitation/revoke
```

The request requires `confirmation: true` and a non-blank audit `reason` of at
most 512 characters. A human caller with `tenant:membership-manage` may revoke
a pending invitation in the selected Tenant; Merchant-scoped callers may only
revoke invitations whose proposed Merchant scope intersects their effective
Merchant set. An unavailable or out-of-scope invitation is returned as `404`.

Revocation locks the invitation and membership in one Core PostgreSQL
transaction, changes `INVITED/PENDING` to `REVOKED/REVOKED`, appends the audit
evidence, and emits the versioned `IdentityLifecycleChanged` outbox event. A
consumed or already revoked invitation returns `409`. The response contains
only Tenant, membership, invitation, status, and membership-version metadata;
it never contains the invitation token hash or other secret material.

The operational-contact contract is Tenant-scoped and versioned:

```text
GET /api/v1/tenants/{tenantId}/operational-contacts
GET /api/v1/tenants/{tenantId}/operational-contacts/{contactId}
PUT /api/v1/tenants/{tenantId}/operational-contacts/{contactId}
```

The collection returns the current version of each contact. A contact update
appends a new immutable version for the same `contactId`; its Tenant ownership
cannot change. The request contains `displayName`, `email`, `purpose`, and
`active`, plus `confirmation: true` and a non-blank `reason`. Email is
normalized to lowercase, the update requires Tenant-wide `tenant:configure`,
reads require `tenant:read`, and the reason is preserved in audit evidence.
The item read returns `404` when the contact does not exist. Release 0.3 does
not send email; these records are operational contact evidence only.

During the Slice 2B HTTP migration, the existing
`POST /api/v1/tenants/{tenantId}/activate` compatibility route is owned by the
Administration module and requires an authenticated Platform Admin. The
historical Release 0.1 OpenAPI file remains unchanged; the canonical
`/api/v1/platform/tenants/...` routes will be published by the complete Release
0.3 OpenAPI contract.

## Service Payment contract

`POST /api/v1/payments`

Request content:

- merchant reference;
- amount;
- currency;
- customer identifier;
- payment-method category;
- idempotency key.

`tenantId` and `merchantId` are absent. They are derived from the active OAuth client/credential.

Idempotency remains `tenantId + idempotencyKey`; Merchant is fingerprinted canonical content.

## Sensitive actions

Use explicit action resources rather than arbitrary status mutation, for example:

```text
POST .../reversals
POST .../reversals/{id}/retry
POST .../payments/{id}/retry-now
POST .../risk-reviews/{id}/decisions
POST .../reconciliation-runs/{id}/promote
POST .../cases/{id}/resolution
POST .../corrections
```

Each action has typed confirmation/reason fields where required and returns the stable logical result on exact replay.

## Event contracts

ADR-025 defines producers/topics/dedup keys. Required new JSON Schemas include:

- `SubmitReversalToProvider`
- `ProviderReversalResultObserved`
- `ReversalCompleted`
- `ReversalFailed`
- `CreateCaseRequested`
- Tenant/Merchant lifecycle events
- Membership/Credential/Support lifecycle events
- Risk review/configuration events
- Case/Correction lifecycle events
- Settlement batch/run/discrepancy/current/posting events

## Merchant webhook contract

Separate from Provider webhooks. Document:

- endpoint registration/rotation/revocation;
- stable event ID and versioned JSON payload;
- exact HMAC canonical bytes and headers;
- timestamp/replay guidance;
- at-least-once behavior and recipient idempotency;
- timeout/retry/final status;
- sandbox-only data and URL restrictions.

## Contract verification

- OpenAPI schema validation and executable MockMvc contract tests;
- valid/invalid JSON Schema fixtures;
- cross-application Provider HMAC fixtures;
- merchant webhook sender/receiver golden fixtures;
- preceding-version consumer compatibility;
- no undocumented endpoint or status mutation;
- API auth/permission/Tenant/Merchant negative matrix.


## Provider Simulator v2

Payment and Reversal submission bodies include the pinned ADR-027 scenario profile ID, version, and canonical snapshot. HMAC canonicalization remains the ADR-021 body-hash contract. Status query remains stable-key based. The Simulator stores the scenario snapshot and uses it for responses, webhooks, and settlement generation.

## SSE contract

Tenant-scoped SSE uses persisted projection event IDs and supports `Last-Event-ID`. An unavailable cursor returns an explicit resync event; Tenant change closes the old stream before the new snapshot/stream opens.


## Provider settlement file contract

The exact Release 0.3 CSV format, validation semantics, dual canonical-record/physical-occurrence identity, deterministic matching, and settlement-posting relationship are defined in [Provider settlement file contract v1](provider-settlement-file-v1.md). Slice 7 must not invent another format or identity.
