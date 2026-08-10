CREATE TABLE payment.reversals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_by UUID NOT NULL,
    request_reason TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    processing_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failure_category TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_reversal_tenant_payment UNIQUE (tenant_id, payment_id),
    CONSTRAINT fk_reversal_payment
        FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment.payments (tenant_id, id),
    CONSTRAINT ck_reversal_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_reversal_currency_format CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_reversal_status CHECK (
        status IN ('REQUESTED', 'PROCESSING', 'FAILED', 'COMPLETED')
    ),
    CONSTRAINT ck_reversal_reason_not_blank CHECK (length(trim(request_reason)) > 0),
    CONSTRAINT ck_reversal_version_nonnegative CHECK (version >= 0),
    CONSTRAINT ck_reversal_timestamp_order CHECK (
        (processing_at IS NULL OR processing_at >= requested_at)
        AND (failed_at IS NULL OR failed_at >= requested_at)
        AND (completed_at IS NULL OR completed_at >= requested_at)
    ),
    CONSTRAINT ck_reversal_status_shape CHECK (
        (status = 'REQUESTED'
            AND processing_at IS NULL AND failed_at IS NULL
            AND completed_at IS NULL AND failure_category IS NULL)
        OR (status = 'PROCESSING'
            AND processing_at IS NOT NULL AND completed_at IS NULL
            AND ((failed_at IS NULL AND failure_category IS NULL)
                OR (failed_at IS NOT NULL AND failure_category IS NOT NULL)))
        OR (status = 'FAILED'
            AND processing_at IS NOT NULL AND failed_at IS NOT NULL
            AND completed_at IS NULL AND failure_category IS NOT NULL
            AND length(trim(failure_category)) > 0)
        OR (status = 'COMPLETED'
            AND processing_at IS NOT NULL AND completed_at IS NOT NULL
            AND ((failed_at IS NULL AND failure_category IS NULL)
                OR (failed_at IS NOT NULL AND failure_category IS NOT NULL)))
    )
);

CREATE INDEX ix_reversal_tenant_status
    ON payment.reversals (tenant_id, status, updated_at);

CREATE FUNCTION payment.reject_reversal_business_content_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.merchant_id IS DISTINCT FROM OLD.merchant_id
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.currency IS DISTINCT FROM OLD.currency
       OR NEW.requested_by IS DISTINCT FROM OLD.requested_by
       OR NEW.request_reason IS DISTINCT FROM OLD.request_reason
       OR NEW.requested_at IS DISTINCT FROM OLD.requested_at THEN
        RAISE EXCEPTION 'Reversal request identity and content are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER reversal_business_content_immutable
BEFORE UPDATE ON payment.reversals
FOR EACH ROW EXECUTE FUNCTION payment.reject_reversal_business_content_mutation();

ALTER TABLE payment.payment_attempts
    ADD COLUMN attempt_subject_type VARCHAR(16),
    ADD COLUMN attempt_subject_id UUID;

UPDATE payment.payment_attempts
   SET attempt_subject_type = 'PAYMENT',
       attempt_subject_id = payment_id;

ALTER TABLE payment.payment_attempts
    ALTER COLUMN attempt_subject_type SET NOT NULL,
    ALTER COLUMN attempt_subject_id SET NOT NULL,
    DROP CONSTRAINT uk_payment_attempt_sequence,
    DROP CONSTRAINT ck_payment_attempt_provider_key,
    ADD CONSTRAINT uk_payment_attempt_subject_sequence
        UNIQUE (tenant_id, attempt_subject_type, attempt_subject_id, sequence),
    ADD CONSTRAINT ck_payment_attempt_subject_type
        CHECK (attempt_subject_type IN ('PAYMENT', 'REVERSAL')),
    ADD CONSTRAINT ck_payment_attempt_subject_identity
        CHECK (
            (attempt_subject_type = 'PAYMENT' AND attempt_subject_id = payment_id)
            OR attempt_subject_type = 'REVERSAL'
        ),
    ADD CONSTRAINT ck_payment_attempt_provider_key
        CHECK (
            (attempt_subject_type = 'PAYMENT'
                AND provider_idempotency_key = 'payment:' || lower(attempt_subject_id::text))
            OR (attempt_subject_type = 'REVERSAL'
                AND provider_idempotency_key = 'reversal:' || lower(attempt_subject_id::text))
        );

CREATE FUNCTION payment.validate_reversal_attempt_subject()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.attempt_subject_type = 'REVERSAL'
       AND NOT EXISTS (
           SELECT 1
             FROM payment.reversals r
            WHERE r.tenant_id = NEW.tenant_id
              AND r.id = NEW.attempt_subject_id
       ) THEN
        RAISE EXCEPTION 'Reversal Payment Attempt subject does not exist';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_attempt_subject_exists
BEFORE INSERT ON payment.payment_attempts
FOR EACH ROW EXECUTE FUNCTION payment.validate_reversal_attempt_subject();
