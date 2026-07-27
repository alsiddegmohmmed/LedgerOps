# Release 0.3 Slice 7 - Settlement generation, upload, validation, and durable ingestion

Status: Pending  
Owner: One implementation owner  
Release: 0.3

## Outcome

Provider Simulator generates deterministic settlement files. Core streams uploads to immutable object storage, identifies duplicates/corrections, validates without partial silent acceptance, and ingests normalized immutable record versions through restartable Spring Batch.

## Authority

REC-01; PRV-01; BR-01/12/15; ADR-010, ADR-024, ADR-027; `docs/api/provider-settlement-file-v1.md`.

## Scope

- exact `docs/api/provider-settlement-file-v1.md` CSV contract and deterministic Provider Simulator generation for Payment and Reversal records;
- stable provider record key and corrected-version scenarios;
- size/type/schema limits and upload authorization;
- content-addressed MinIO/S3 storage outside DB transaction;
- batch family/version metadata/hash/supersession;
- exact duplicate return-existing behavior;
- exact batch lifecycle `RECEIVED -> VALIDATING -> READY -> PROCESSING -> COMPLETED | COMPLETED_WITH_DISCREPANCIES | FAILED`;
- validation summary and explicit Reconciliation Analyst confirmation before `READY -> PROCESSING`;
- structural/file-identity failures import nothing; record errors remain quarantined evidence and are never silently dropped;
- dual identity: canonical immutable record versions unique by Provider key/content hash plus physical occurrences unique by batch version/row number;
- Spring Batch streaming/chunking/restart metadata;
- upload/validation/batch UI and audit.

Excluded:

- matching, current-run promotion, discrepancies, Ledger posting.

## Critical invariants

- raw object is immutable and never overwritten;
- DB never claims a usable object before successful object write;
- orphan content-addressed objects are harmless and cleanup-safe;
- exact duplicate creates no second batch/version;
- corrected content creates linked immutable version;
- malformed/unsupported records are reported deterministically; structural failure produces `FAILED`, while explicit import of valid rows with quarantined invalid rows produces `COMPLETED_WITH_DISCREPANCIES`;
- no whole-file in-memory load;
- Provider Simulator cannot access Core DB.

## Verification

- hash/object key/idempotency;
- DB failure after object put and safe retry;
- duplicate/corrected batch concurrency;
- malformed CSV, unsupported currency, duplicate record keys, size limits;
- chunk crash/restart, no duplicate normalized rows, and canonical-version reuse across corrected files;
- 100,000-record memory-bounded ingestion;
- object storage outage/recovery;
- tenant isolation and audit;
- full commands.

## Completion report

- Changed: Pending
- Verified: Pending
- Incomplete: Slice 7
- Deviations: None
