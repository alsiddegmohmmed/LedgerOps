CREATE SCHEMA messaging;

ALTER TABLE payment.payments
    ADD CONSTRAINT uk_payments_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE payment.payment_attempts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    sequence INTEGER NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    provider_idempotency_key TEXT NOT NULL,
    initiated_at TIMESTAMPTZ NOT NULL,
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    currency CHAR(3) NOT NULL,
    payment_method_category TEXT NOT NULL,
    request_intent_hash CHAR(64) NOT NULL,

    CONSTRAINT fk_payment_attempt_payment
        FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment.payments (tenant_id, id),
    CONSTRAINT uk_payment_attempt_sequence
        UNIQUE (tenant_id, payment_id, sequence),
    CONSTRAINT ck_payment_attempt_sequence_positive CHECK (sequence > 0),
    CONSTRAINT ck_payment_attempt_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_payment_attempt_provider_key
        CHECK (provider_idempotency_key = 'payment:' || lower(payment_id::text)),
    CONSTRAINT ck_payment_attempt_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_payment_attempt_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_payment_attempt_method CHECK (length(trim(payment_method_category)) > 0),
    CONSTRAINT ck_payment_attempt_hash CHECK (request_intent_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_payment_attempts_tenant_payment
    ON payment.payment_attempts (tenant_id, payment_id, sequence);

CREATE FUNCTION payment.reject_payment_attempt_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Payment Attempts are immutable';
END;
$$;

CREATE TRIGGER payment_attempts_immutable
BEFORE UPDATE OR DELETE ON payment.payment_attempts
FOR EACH ROW EXECUTE FUNCTION payment.reject_payment_attempt_mutation();

CREATE TABLE messaging.outbox (
    id UUID PRIMARY KEY,
    message_id UUID NOT NULL UNIQUE,
    producer_name VARCHAR(16) NOT NULL,
    deduplication_key TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    message_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    topic TEXT NOT NULL,
    partition_key TEXT NOT NULL,
    payload TEXT NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_outbox_business_identity
        UNIQUE (producer_name, deduplication_key),
    CONSTRAINT ck_outbox_producer CHECK (producer_name IN ('payment', 'provider')),
    CONSTRAINT ck_outbox_content_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_outbox_schema_version CHECK (schema_version = 1),
    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload::jsonb) = 'object'),
    CONSTRAINT ck_outbox_status CHECK (status = 'PENDING')
);

CREATE INDEX ix_outbox_tenant_aggregate
    ON messaging.outbox (tenant_id, aggregate_id, message_type);

CREATE FUNCTION messaging.reject_outbox_business_content_mutation()
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
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Outbox message identity and content are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER outbox_business_content_immutable
BEFORE UPDATE ON messaging.outbox
FOR EACH ROW EXECUTE FUNCTION messaging.reject_outbox_business_content_mutation();
