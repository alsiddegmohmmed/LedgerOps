ALTER TABLE provider.work
    ADD COLUMN attempt_sequence INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN transport_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_provider_work_attempt_sequence CHECK (
        attempt_sequence BETWEEN 1 AND 3
    ),
    ADD CONSTRAINT ck_provider_work_transport_retry_count CHECK (
        transport_retry_count BETWEEN 0 AND 1
    );

ALTER TABLE provider.work
    ALTER COLUMN attempt_sequence DROP DEFAULT;

ALTER TABLE payment.payment_attempts
    ADD CONSTRAINT ck_payment_attempt_release_0_2_limit CHECK (sequence <= 3);

CREATE OR REPLACE FUNCTION provider.reject_work_business_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.attempt_sequence IS DISTINCT FROM OLD.attempt_sequence
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

ALTER TABLE provider.results
    ADD CONSTRAINT uk_provider_result_id_tenant UNIQUE (evidence_id, tenant_id);

CREATE TABLE provider.retry_requests (
    retry_request_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    previous_attempt_id UUID NOT NULL,
    provider_evidence_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_provider_retry_request_evidence
        UNIQUE (tenant_id, provider_evidence_id),
    CONSTRAINT fk_provider_retry_request_evidence
        FOREIGN KEY (provider_evidence_id, tenant_id)
        REFERENCES provider.results (evidence_id, tenant_id),
    CONSTRAINT ck_provider_retry_request_provider CHECK (provider_id = 'SIMULATOR')
);

CREATE TABLE payment.retry_applications (
    tenant_id UUID NOT NULL,
    retry_request_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    previous_attempt_id UUID NOT NULL,
    new_attempt_id UUID NOT NULL,
    provider_evidence_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, retry_request_id),
    CONSTRAINT uk_payment_retry_evidence UNIQUE (tenant_id, provider_evidence_id),
    CONSTRAINT uk_payment_retry_new_attempt UNIQUE (tenant_id, new_attempt_id),
    CONSTRAINT fk_payment_retry_payment
        FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment.payments (tenant_id, id),
    CONSTRAINT fk_payment_retry_previous_attempt
        FOREIGN KEY (tenant_id, payment_id, previous_attempt_id)
        REFERENCES payment.payment_attempts (tenant_id, payment_id, id),
    CONSTRAINT fk_payment_retry_new_attempt
        FOREIGN KEY (tenant_id, payment_id, new_attempt_id)
        REFERENCES payment.payment_attempts (tenant_id, payment_id, id),
    CONSTRAINT ck_payment_retry_provider CHECK (provider_id = 'SIMULATOR')
);

CREATE FUNCTION provider.reject_retry_request_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Provider retry requests are immutable';
END;
$$;

CREATE TRIGGER provider_retry_request_immutable
BEFORE UPDATE OR DELETE ON provider.retry_requests
FOR EACH ROW EXECUTE FUNCTION provider.reject_retry_request_mutation();

CREATE FUNCTION payment.reject_retry_application_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Payment retry applications are immutable';
END;
$$;

CREATE TRIGGER payment_retry_application_immutable
BEFORE UPDATE OR DELETE ON payment.retry_applications
FOR EACH ROW EXECUTE FUNCTION payment.reject_retry_application_mutation();

ALTER TABLE provider.operational_events
    DROP CONSTRAINT ck_provider_operational_event_type;

ALTER TABLE provider.operational_events
    ADD CONSTRAINT ck_provider_operational_event_type CHECK (
        event_type IN (
            'SUBMISSION_OUTCOME_AMBIGUOUS',
            'STATUS_RECOVERY_EXHAUSTED',
            'SAFE_RETRY_EXHAUSTED'
        )
    );
