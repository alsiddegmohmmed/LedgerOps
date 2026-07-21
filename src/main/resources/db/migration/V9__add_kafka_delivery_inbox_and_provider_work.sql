ALTER TABLE messaging.outbox
    DROP CONSTRAINT ck_outbox_status;

ALTER TABLE messaging.outbox
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN lease_owner TEXT,
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN last_error_code TEXT,
    ADD COLUMN last_error_summary VARCHAR(512),
    ADD COLUMN traceparent VARCHAR(128),
    ADD COLUMN tracestate VARCHAR(512);

UPDATE messaging.outbox
SET next_attempt_at = created_at
WHERE next_attempt_at IS NULL;

ALTER TABLE messaging.outbox
    ALTER COLUMN next_attempt_at SET NOT NULL,
    ADD CONSTRAINT ck_outbox_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'RETRYABLE', 'PUBLISHED', 'DEAD')
    ),
    ADD CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count BETWEEN 0 AND 10),
    ADD CONSTRAINT ck_outbox_lease_shape CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR
        (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    );

CREATE INDEX ix_outbox_due
    ON messaging.outbox (status, next_attempt_at, lease_expires_at);

CREATE OR REPLACE FUNCTION messaging.reject_outbox_business_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.message_id IS DISTINCT FROM OLD.message_id
       OR NEW.producer_name IS DISTINCT FROM OLD.producer_name
       OR NEW.deduplication_key IS DISTINCT FROM OLD.deduplication_key
       OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
       OR NEW.message_type IS DISTINCT FROM OLD.message_type
       OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
       OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.topic IS DISTINCT FROM OLD.topic
       OR NEW.partition_key IS DISTINCT FROM OLD.partition_key
       OR NEW.payload IS DISTINCT FROM OLD.payload
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
       OR NEW.traceparent IS DISTINCT FROM OLD.traceparent
       OR NEW.tracestate IS DISTINCT FROM OLD.tracestate
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Outbox message identity and content are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE messaging.inbox (
    consumer_name TEXT NOT NULL,
    message_id UUID NOT NULL,
    tenant_id UUID,
    message_type TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_name, message_id),
    CONSTRAINT ck_inbox_status CHECK (status IN ('PROCESSED', 'DEAD'))
);

CREATE TABLE messaging.consumer_failures (
    consumer_name TEXT NOT NULL,
    message_id UUID NOT NULL,
    tenant_id UUID,
    failure_count INTEGER NOT NULL,
    first_failed_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ NOT NULL,
    last_reason VARCHAR(512) NOT NULL,
    PRIMARY KEY (consumer_name, message_id),
    CONSTRAINT ck_consumer_failure_count CHECK (failure_count BETWEEN 1 AND 5)
);

CREATE TABLE messaging.publication_dead_letters (
    id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL UNIQUE,
    reason_code TEXT NOT NULL,
    safe_summary VARCHAR(512) NOT NULL,
    dead_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_publication_dead_letter_outbox
        FOREIGN KEY (outbox_id) REFERENCES messaging.outbox (id)
);

CREATE TABLE messaging.consumer_dead_letters (
    id UUID PRIMARY KEY,
    consumer_name TEXT NOT NULL,
    message_id UUID NOT NULL,
    tenant_id UUID,
    message_type TEXT NOT NULL,
    envelope TEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    topic TEXT NOT NULL,
    partition_number INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    reason_code TEXT NOT NULL,
    safe_summary VARCHAR(512) NOT NULL,
    correlation_id UUID,
    first_failed_at TIMESTAMPTZ NOT NULL,
    dead_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_consumer_dead_letter UNIQUE (consumer_name, message_id),
    CONSTRAINT ck_consumer_dead_payload_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE messaging.transport_dead_letters (
    id UUID PRIMARY KEY,
    consumer_name TEXT NOT NULL,
    topic TEXT NOT NULL,
    partition_number INTEGER NOT NULL,
    record_offset BIGINT NOT NULL,
    raw_record_hash CHAR(64) NOT NULL,
    bounded_safe_bytes BYTEA,
    safe_summary VARCHAR(512) NOT NULL,
    reason_code TEXT NOT NULL,
    correlation_id UUID,
    dead_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_transport_dead_letter
        UNIQUE (consumer_name, topic, partition_number, record_offset),
    CONSTRAINT ck_transport_dead_hash CHECK (raw_record_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transport_dead_bytes CHECK (
        bounded_safe_bytes IS NULL OR octet_length(bounded_safe_bytes) <= 4096
    )
);

CREATE SCHEMA provider;

CREATE TABLE provider.work (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    work_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    provider_idempotency_key TEXT NOT NULL,
    request_intent_hash CHAR(64) NOT NULL,
    command_payload TEXT NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_provider_work_business UNIQUE (tenant_id, attempt_id, work_type),
    CONSTRAINT ck_provider_work_type CHECK (work_type IN ('SUBMISSION', 'STATUS_QUERY')),
    CONSTRAINT ck_provider_work_status CHECK (
        status IN (
            'PENDING', 'RETRYABLE', 'CLAIMED', 'WAITING_RETRY_REQUEST',
            'WAITING_STATUS', 'COMPLETED', 'UNRESOLVED'
        )
    ),
    CONSTRAINT ck_provider_work_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_work_key CHECK (
        provider_idempotency_key = 'payment:' || lower(payment_id::text)
    ),
    CONSTRAINT ck_provider_work_hash CHECK (request_intent_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_provider_work_payload CHECK (
        jsonb_typeof(command_payload::jsonb) = 'object'
    ),
    CONSTRAINT ck_provider_work_lease_shape CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR
        (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_provider_work_due
    ON provider.work (status, due_at, lease_expires_at);

CREATE FUNCTION provider.reject_work_business_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.work_type IS DISTINCT FROM OLD.work_type
       OR NEW.provider_id IS DISTINCT FROM OLD.provider_id
       OR NEW.provider_idempotency_key IS DISTINCT FROM OLD.provider_idempotency_key
       OR NEW.request_intent_hash IS DISTINCT FROM OLD.request_intent_hash
       OR NEW.command_payload IS DISTINCT FROM OLD.command_payload
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.causation_id IS DISTINCT FROM OLD.causation_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Provider work business identity and content are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_work_business_content_immutable
BEFORE UPDATE ON provider.work
FOR EACH ROW EXECUTE FUNCTION provider.reject_work_business_content_mutation();
