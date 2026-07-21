CREATE TABLE provider.platform_security_rejections (
    rejection_id UUID PRIMARY KEY,
    reason_code VARCHAR(64) NOT NULL,
    key_id VARCHAR(128),
    raw_body_hash CHAR(64) NOT NULL,
    body_size INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_platform_rejection_hash CHECK (raw_body_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_rejection_size CHECK (body_size >= 0),
    CONSTRAINT ck_platform_rejection_reason CHECK (reason_code IN (
        'BODY_TOO_LARGE', 'UNKNOWN_OR_WRONG_DIRECTION_KEY',
        'INVALID_TIMESTAMP', 'INVALID_EVENT_ID', 'INVALID_SIGNATURE'
    ))
);

CREATE TABLE provider.unattributed_webhook_evidence (
    evidence_id UUID PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL,
    provider_client_id VARCHAR(64) NOT NULL,
    provider_event_id UUID,
    payload_hash CHAR(64) NOT NULL,
    canonical_payload TEXT,
    outcome VARCHAR(32) NOT NULL,
    safe_reason_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_unattributed_webhook_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_unattributed_webhook_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_unattributed_webhook_outcome CHECK (
        outcome IN ('INVALID_JSON', 'UNMAPPED')
    ),
    CONSTRAINT ck_unattributed_webhook_payload CHECK (
        (outcome = 'INVALID_JSON' AND canonical_payload IS NULL)
        OR (outcome = 'UNMAPPED' AND jsonb_typeof(canonical_payload::jsonb) = 'object')
    )
);

CREATE TABLE provider.webhook_events (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    provider_client_id VARCHAR(64) NOT NULL,
    provider_event_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    attempt_sequence INTEGER NOT NULL,
    provider_idempotency_key TEXT NOT NULL,
    provider_result_id UUID NOT NULL,
    provider_reference TEXT,
    result_category VARCHAR(32) NOT NULL,
    provider_occurred_at TIMESTAMPTZ NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    canonical_payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    lease_owner TEXT,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    correlation_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_provider_webhook_event UNIQUE (
        tenant_id, provider_id, provider_event_id
    ),
    CONSTRAINT uk_provider_webhook_event_id_tenant UNIQUE (event_id, tenant_id),
    CONSTRAINT ck_provider_webhook_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_webhook_attempt_sequence CHECK (attempt_sequence BETWEEN 1 AND 3),
    CONSTRAINT ck_provider_webhook_key CHECK (
        provider_idempotency_key = 'payment:' || lower(payment_id::text)
    ),
    CONSTRAINT ck_provider_webhook_category CHECK (result_category IN (
        'SUCCESS', 'ACCEPTED', 'DECLINED', 'PENDING',
        'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'UNKNOWN'
    )),
    CONSTRAINT ck_provider_webhook_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_provider_webhook_payload CHECK (
        jsonb_typeof(canonical_payload::jsonb) = 'object'
    ),
    CONSTRAINT ck_provider_webhook_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'UNRESOLVED')
    ),
    CONSTRAINT ck_provider_webhook_lease CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_provider_webhook_event_claim
    ON provider.webhook_events (status, received_at, lease_expires_at);

CREATE TABLE provider.webhook_receipts (
    receipt_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    event_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    provider_event_id UUID NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    correlation_id UUID NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_provider_webhook_receipt_event
        FOREIGN KEY (event_id, tenant_id)
        REFERENCES provider.webhook_events (event_id, tenant_id),
    CONSTRAINT ck_provider_webhook_receipt_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_webhook_receipt_hash CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_provider_webhook_receipt_outcome CHECK (
        outcome IN ('NEW', 'DUPLICATE', 'CONFLICT')
    )
);

CREATE TABLE provider.webhook_operational_events (
    operational_event_id UUID PRIMARY KEY,
    tenant_id UUID,
    event_id UUID,
    provider_id VARCHAR(32) NOT NULL,
    provider_event_id UUID,
    event_type VARCHAR(32) NOT NULL,
    safe_reason_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_provider_webhook_operational_event
        FOREIGN KEY (event_id, tenant_id)
        REFERENCES provider.webhook_events (event_id, tenant_id),
    CONSTRAINT ck_provider_webhook_operational_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_webhook_operational_type CHECK (
        event_type IN ('UNMAPPED', 'PAYLOAD_CONFLICT', 'RESULT_CONFLICT')
    ),
    CONSTRAINT ck_provider_webhook_operational_attribution CHECK (
        (tenant_id IS NULL AND event_id IS NULL)
        OR (tenant_id IS NOT NULL AND event_id IS NOT NULL)
    )
);

ALTER TABLE provider.interactions
    ALTER COLUMN work_id DROP NOT NULL,
    ADD COLUMN webhook_event_id UUID,
    ADD CONSTRAINT fk_provider_interaction_webhook_event
        FOREIGN KEY (webhook_event_id, tenant_id)
        REFERENCES provider.webhook_events (event_id, tenant_id),
    ADD CONSTRAINT ck_provider_interaction_source CHECK (
        (work_id IS NOT NULL AND webhook_event_id IS NULL)
        OR (work_id IS NULL AND webhook_event_id IS NOT NULL)
    );

ALTER TABLE provider.interactions
    DROP CONSTRAINT ck_provider_interaction_work_type,
    ADD CONSTRAINT ck_provider_interaction_work_type CHECK (
        work_type IN ('SUBMISSION', 'STATUS_QUERY', 'WEBHOOK')
    );

ALTER TABLE provider.results
    ALTER COLUMN work_id DROP NOT NULL,
    ADD COLUMN webhook_event_id UUID,
    ADD CONSTRAINT fk_provider_result_webhook_event
        FOREIGN KEY (webhook_event_id, tenant_id)
        REFERENCES provider.webhook_events (event_id, tenant_id),
    ADD CONSTRAINT ck_provider_result_source CHECK (
        (work_id IS NOT NULL AND webhook_event_id IS NULL)
        OR (work_id IS NULL AND webhook_event_id IS NOT NULL)
    );

CREATE FUNCTION provider.reject_webhook_evidence_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Provider webhook evidence is immutable';
END;
$$;

CREATE TRIGGER platform_security_rejection_immutable
BEFORE UPDATE OR DELETE ON provider.platform_security_rejections
FOR EACH ROW EXECUTE FUNCTION provider.reject_webhook_evidence_mutation();

CREATE TRIGGER unattributed_webhook_evidence_immutable
BEFORE UPDATE OR DELETE ON provider.unattributed_webhook_evidence
FOR EACH ROW EXECUTE FUNCTION provider.reject_webhook_evidence_mutation();

CREATE TRIGGER provider_webhook_receipt_immutable
BEFORE UPDATE OR DELETE ON provider.webhook_receipts
FOR EACH ROW EXECUTE FUNCTION provider.reject_webhook_evidence_mutation();

CREATE TRIGGER provider_webhook_operational_event_immutable
BEFORE UPDATE OR DELETE ON provider.webhook_operational_events
FOR EACH ROW EXECUTE FUNCTION provider.reject_webhook_evidence_mutation();

CREATE FUNCTION provider.reject_webhook_event_business_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.event_id IS DISTINCT FROM OLD.event_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.provider_id IS DISTINCT FROM OLD.provider_id
       OR NEW.provider_client_id IS DISTINCT FROM OLD.provider_client_id
       OR NEW.provider_event_id IS DISTINCT FROM OLD.provider_event_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.attempt_sequence IS DISTINCT FROM OLD.attempt_sequence
       OR NEW.provider_idempotency_key IS DISTINCT FROM OLD.provider_idempotency_key
       OR NEW.provider_result_id IS DISTINCT FROM OLD.provider_result_id
       OR NEW.provider_reference IS DISTINCT FROM OLD.provider_reference
       OR NEW.result_category IS DISTINCT FROM OLD.result_category
       OR NEW.provider_occurred_at IS DISTINCT FROM OLD.provider_occurred_at
       OR NEW.payload_hash IS DISTINCT FROM OLD.payload_hash
       OR NEW.canonical_payload IS DISTINCT FROM OLD.canonical_payload
       OR NEW.correlation_id IS DISTINCT FROM OLD.correlation_id
       OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION 'Provider webhook event business content is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_webhook_event_business_immutable
BEFORE UPDATE ON provider.webhook_events
FOR EACH ROW EXECUTE FUNCTION provider.reject_webhook_event_business_mutation();

CREATE TRIGGER provider_webhook_event_delete_prohibited
BEFORE DELETE ON provider.webhook_events
FOR EACH ROW EXECUTE FUNCTION provider.reject_webhook_evidence_mutation();
