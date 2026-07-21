# Release 0.2 operations runbook

Use this runbook for local and test-environment diagnosis. Release 0.2 provides no public replay or mutation endpoint. Preserve dead and unresolved evidence for review.

Use the `LedgerOps Release 0.2 Messaging` dashboard for delivery failures and the
`LedgerOps Release 0.2 Provider Operations` dashboard for Provider, webhook, and
Payment-processing signals. Metrics intentionally exclude tenant, Payment, attempt,
message, Provider-reference, correlation, and trace identifiers. Use structured
logs and immutable database evidence for record-level investigation.

## Kafka outage or outbox backlog

1. Check `ledgerops_outbox_pending`, `ledgerops_outbox_oldest_age_seconds`, and `ledgerops_outbox_publish_total{outcome}`.
2. Verify Kafka connectivity and topic availability without changing outbox rows.
3. Restore Kafka. Due `PENDING` and `RETRYABLE` records publish automatically; expired `CLAIMED` records receive a new fenced lease.
4. Confirm backlog and oldest age return to zero or a stable expected value.

Do not edit an outbox row or clear its lease manually. The claim lease and fencing
token recover expired work without allowing stale publishers to update it.

## Consumer lag or dead message

1. Check `ledgerops_kafka_consumer_lag{consumer,topic}`, `ledgerops_consumer_dead_total{consumer,reason}`, and `ledgerops_trace_context_invalid_total{boundary}`.
2. Inspect the matching `messaging.consumer_dead_letters` or `messaging.transport_dead_letters` row by its documented identity.
3. Correlate the bounded reason, message metadata, and structured logs. Do not edit the inbox or dead-letter record.
4. Escalate schema, contract, or consistency defects with the message ID, consumer name, correlation ID, and safe reason code.

Malformed transport records without a trustworthy message ID appear in
`messaging.transport_dead_letters`. Valid envelopes that are permanently invalid or
reach failure five appear in `messaging.consumer_dead_letters` with inbox status
`DEAD`.

Invalid optional W3C trace metadata does not dead-letter an otherwise valid business
message. LedgerOps drops the invalid context, starts a new trace, and increments
`ledgerops_trace_context_invalid_total{boundary}` for operational investigation.

## Provider outage or ambiguous outcome

1. Check Provider latency, timeout, ambiguity, circuit-state, retry-backlog, and recovery-backlog metrics.
2. Inspect immutable Provider interaction/result evidence and current fenced work state.
3. For `UNKNOWN`, wait for status recovery. Never resubmit blindly.
4. If recovery exhausts, preserve `UNRESOLVED` evidence and keep the Payment `PROCESSING` for authorized later handling.

Only durable evidence marked `SAFE_TO_RESUBMIT` can create
`PaymentSubmissionRetryRequested`. If a Provider transaction exists, use status
recovery; do not resubmit.

## Webhook backlog or signature failures

1. Check `ledgerops_webhook_receipt_total{verification}` and webhook-processing metrics.
2. For `401`, inspect bounded platform rejection evidence. Do not infer tenant identity from payload data.
3. For mapped backlog, confirm lease expiry and worker health. Expired work is reclaimed with a new token.
4. For duplicate or conflicting events, preserve every receipt and operational event. Never replace an accepted final result.

## Stuck Payment or financial consistency failure

1. Correlate the Payment, accepted-final-result evidence, Provider evidence, inbox, lifecycle outbox, and Ledger source identity.
2. Confirm that a `COMPLETED` Payment has the exact ADR-020 two-entry posting.
3. Treat missing or mismatched posting evidence as critical. Do not create a late posting or normalize the record.
4. The target for `ledgerops_duplicate_financial_effect_total` is always zero. The detector queries duplicate tenant-scoped `PAYMENT` source identities. Any positive value blocks release and requires incident review.

Do not create a late Ledger posting for a `COMPLETED` Payment. Exact ADR-020 replay
verification must either return the original logical result or raise a critical
consistency error.
