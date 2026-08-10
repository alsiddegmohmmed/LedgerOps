CREATE TABLE payment.reversal_retry_applications (
    tenant_id UUID NOT NULL,
    reversal_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    previous_attempt_id UUID NOT NULL,
    new_attempt_id UUID NOT NULL,
    provider_evidence_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    request_reason TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (tenant_id, reversal_id, previous_attempt_id),
    CONSTRAINT uk_reversal_retry_new_attempt
        UNIQUE (tenant_id, new_attempt_id),
    CONSTRAINT uk_reversal_retry_evidence
        UNIQUE (tenant_id, provider_evidence_id),
    CONSTRAINT fk_reversal_retry_reversal
        FOREIGN KEY (tenant_id, reversal_id)
        REFERENCES payment.reversals (tenant_id, id),
    CONSTRAINT fk_reversal_retry_payment
        FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment.payments (tenant_id, id),
    CONSTRAINT fk_reversal_retry_previous_attempt
        FOREIGN KEY (tenant_id, payment_id, previous_attempt_id)
        REFERENCES payment.payment_attempts (tenant_id, payment_id, id),
    CONSTRAINT fk_reversal_retry_new_attempt
        FOREIGN KEY (tenant_id, payment_id, new_attempt_id)
        REFERENCES payment.payment_attempts (tenant_id, payment_id, id),
    CONSTRAINT fk_reversal_retry_evidence
        FOREIGN KEY (provider_evidence_id, tenant_id)
        REFERENCES provider.results (evidence_id, tenant_id),
    CONSTRAINT ck_reversal_retry_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_reversal_retry_reason_not_blank CHECK (length(trim(request_reason)) > 0)
);

CREATE FUNCTION payment.reject_reversal_retry_application_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Reversal retry applications are immutable';
END;
$$;

CREATE TRIGGER reversal_retry_application_immutable
BEFORE UPDATE OR DELETE ON payment.reversal_retry_applications
FOR EACH ROW EXECUTE FUNCTION payment.reject_reversal_retry_application_mutation();
