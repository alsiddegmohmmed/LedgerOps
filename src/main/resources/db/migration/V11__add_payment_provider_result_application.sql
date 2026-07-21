ALTER TABLE payment.payment_attempts
    ADD CONSTRAINT uk_payment_attempt_tenant_payment_id
        UNIQUE (tenant_id, payment_id, id);

CREATE TABLE payment.accepted_final_provider_results (
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    provider_evidence_id UUID NOT NULL,
    provider_result_id UUID NOT NULL,
    final_category VARCHAR(32) NOT NULL,
    provider_reference TEXT,
    applied_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, payment_id),
    CONSTRAINT fk_accepted_final_payment
        FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment.payments (tenant_id, id),
    CONSTRAINT fk_accepted_final_attempt
        FOREIGN KEY (tenant_id, payment_id, attempt_id)
        REFERENCES payment.payment_attempts (tenant_id, payment_id, id),
    CONSTRAINT ck_accepted_final_category CHECK (
        final_category IN ('SUCCESS', 'DECLINED', 'PERMANENT_FAILURE')
    )
);

CREATE UNIQUE INDEX uk_accepted_final_provider_evidence
    ON payment.accepted_final_provider_results (tenant_id, provider_evidence_id);

CREATE FUNCTION payment.reject_accepted_final_result_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Accepted final Provider results are immutable';
END;
$$;

CREATE TRIGGER accepted_final_provider_result_immutable
BEFORE UPDATE OR DELETE ON payment.accepted_final_provider_results
FOR EACH ROW EXECUTE FUNCTION payment.reject_accepted_final_result_mutation();
