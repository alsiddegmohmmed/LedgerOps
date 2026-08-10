ALTER TABLE payment.reversals
    ADD CONSTRAINT uk_reversal_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE payment.accepted_final_reversal_results (
    tenant_id UUID NOT NULL,
    reversal_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    provider_evidence_id UUID NOT NULL,
    provider_result_id UUID NOT NULL,
    final_category VARCHAR(32) NOT NULL,
    provider_reference TEXT,
    applied_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, reversal_id),
    CONSTRAINT fk_accepted_reversal
        FOREIGN KEY (tenant_id, reversal_id)
        REFERENCES payment.reversals (tenant_id, id),
    CONSTRAINT fk_accepted_reversal_attempt
        FOREIGN KEY (tenant_id, payment_id, attempt_id)
        REFERENCES payment.payment_attempts (tenant_id, payment_id, id),
    CONSTRAINT ck_accepted_reversal_category CHECK (
        final_category IN ('SUCCESS', 'DECLINED', 'TEMPORARY_FAILURE', 'PERMANENT_FAILURE')
    )
);

CREATE UNIQUE INDEX uk_accepted_reversal_provider_evidence
    ON payment.accepted_final_reversal_results (tenant_id, provider_evidence_id);

CREATE FUNCTION payment.reject_accepted_reversal_result_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Accepted final Reversal results are immutable';
END;
$$;

CREATE TRIGGER accepted_final_reversal_result_immutable
BEFORE UPDATE OR DELETE ON payment.accepted_final_reversal_results
FOR EACH ROW EXECUTE FUNCTION payment.reject_accepted_reversal_result_mutation();

CREATE UNIQUE INDEX uk_ledger_one_compensation_per_target
    ON ledger.transactions (tenant_id, compensates_transaction_id)
    WHERE compensates_transaction_id IS NOT NULL;
