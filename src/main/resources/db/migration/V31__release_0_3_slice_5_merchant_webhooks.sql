CREATE SCHEMA notification;

CREATE TABLE notification.webhook_endpoints (
    endpoint_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    label VARCHAR(120) NOT NULL,
    endpoint_url TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    encrypted_secret BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    key_version VARCHAR(64) NOT NULL,
    allowed_event_types TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT ck_notification_endpoint_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_notification_endpoint_label CHECK (length(trim(label)) BETWEEN 1 AND 120),
    CONSTRAINT ck_notification_endpoint_secret CHECK (
        octet_length(encrypted_secret) > 0 AND octet_length(secret_nonce) = 12
    ),
    CONSTRAINT ck_notification_endpoint_events CHECK (cardinality(allowed_event_types) > 0)
);

CREATE INDEX ix_notification_endpoint_tenant_merchant
    ON notification.webhook_endpoints (tenant_id, merchant_id, created_at DESC);

CREATE TABLE notification.webhook_events (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_notification_event_endpoint
        FOREIGN KEY (endpoint_id) REFERENCES notification.webhook_endpoints (endpoint_id),
    CONSTRAINT ck_notification_event_type CHECK (
        event_type IN ('payment.completed', 'payment.failed', 'payment.reversed',
                       'risk.review.required', 'reconciliation.discrepancy.created')
    ),
    CONSTRAINT ck_notification_event_payload CHECK (jsonb_typeof(payload::jsonb) = 'object')
);

CREATE TABLE notification.webhook_deliveries (
    delivery_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner TEXT,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    encrypted_secret BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    key_version VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    last_http_status INTEGER,
    last_outcome VARCHAR(64),
    last_safe_summary VARCHAR(512),
    CONSTRAINT fk_notification_delivery_event
        FOREIGN KEY (event_id) REFERENCES notification.webhook_events (event_id),
    CONSTRAINT fk_notification_delivery_endpoint
        FOREIGN KEY (endpoint_id) REFERENCES notification.webhook_endpoints (endpoint_id),
    CONSTRAINT ck_notification_delivery_status CHECK (
        status IN ('PENDING', 'CLAIMED', 'RETRYABLE', 'DELIVERED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_notification_delivery_attempts CHECK (attempt_count BETWEEN 0 AND 5),
    CONSTRAINT ck_notification_delivery_secret CHECK (
        octet_length(encrypted_secret) > 0 AND octet_length(secret_nonce) = 12
    ),
    CONSTRAINT ck_notification_delivery_lease CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX ix_notification_delivery_claim
    ON notification.webhook_deliveries (status, next_attempt_at, lease_expires_at);

CREATE TABLE notification.webhook_attempts (
    attempt_id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    http_status INTEGER,
    outcome VARCHAR(64) NOT NULL,
    response_body_hash CHAR(64),
    safe_summary VARCHAR(512),
    CONSTRAINT fk_notification_attempt_delivery
        FOREIGN KEY (delivery_id) REFERENCES notification.webhook_deliveries (delivery_id),
    CONSTRAINT uk_notification_attempt_number UNIQUE (delivery_id, attempt_number),
    CONSTRAINT ck_notification_attempt_number CHECK (attempt_number BETWEEN 1 AND 5),
    CONSTRAINT ck_notification_attempt_hash CHECK (
        response_body_hash IS NULL OR response_body_hash ~ '^[0-9a-f]{64}$'
    )
);

CREATE FUNCTION notification.reject_webhook_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Merchant webhook history is append-only';
END;
$$;

CREATE TRIGGER notification_webhook_event_immutable
BEFORE UPDATE OR DELETE ON notification.webhook_events
FOR EACH ROW EXECUTE FUNCTION notification.reject_webhook_history_mutation();

CREATE TRIGGER notification_webhook_attempt_immutable
BEFORE UPDATE OR DELETE ON notification.webhook_attempts
FOR EACH ROW EXECUTE FUNCTION notification.reject_webhook_history_mutation();

CREATE FUNCTION notification.reject_webhook_endpoint_business_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.endpoint_id IS DISTINCT FROM OLD.endpoint_id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Merchant webhook endpoint identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER notification_webhook_endpoint_business_immutable
BEFORE UPDATE ON notification.webhook_endpoints
FOR EACH ROW EXECUTE FUNCTION notification.reject_webhook_endpoint_business_mutation();
