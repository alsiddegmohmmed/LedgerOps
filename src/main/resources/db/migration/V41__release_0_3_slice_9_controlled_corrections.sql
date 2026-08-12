ALTER TABLE reconciliation.settlement_posting_instructions
    ADD CONSTRAINT uk_settlement_instruction_tenant_posting
        UNIQUE (tenant_id, settlement_posting_id);

CREATE TABLE casework.correction_requests (
    correction_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    discrepancy_id UUID NOT NULL,
    settlement_posting_id UUID NOT NULL,
    original_ledger_transaction_id UUID NOT NULL,
    kind VARCHAR(64) NOT NULL,
    requested_by UUID NOT NULL,
    reason TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    compensation_ledger_transaction_id UUID,
    failure_reason VARCHAR(2500),

    CONSTRAINT uk_correction_request_target
        UNIQUE (tenant_id, original_ledger_transaction_id),
    CONSTRAINT fk_correction_request_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES casework.cases (tenant_id, id),
    CONSTRAINT fk_correction_request_settlement_posting
        FOREIGN KEY (tenant_id, settlement_posting_id)
        REFERENCES reconciliation.settlement_posting_instructions
            (tenant_id, settlement_posting_id),
    CONSTRAINT fk_correction_request_original_ledger
        FOREIGN KEY (tenant_id, original_ledger_transaction_id)
        REFERENCES ledger.transactions (tenant_id, id),
    CONSTRAINT fk_correction_request_compensation_ledger
        FOREIGN KEY (tenant_id, compensation_ledger_transaction_id)
        REFERENCES ledger.transactions (tenant_id, id),
    CONSTRAINT ck_correction_request_kind
        CHECK (kind = 'COMPENSATE_SETTLEMENT_ADJUSTMENT'),
    CONSTRAINT ck_correction_request_status
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_correction_request_reason
        CHECK (length(trim(reason)) BETWEEN 1 AND 2000),
    CONSTRAINT ck_correction_request_failure_reason
        CHECK (failure_reason IS NULL OR length(trim(failure_reason)) BETWEEN 1 AND 2500),
    CONSTRAINT ck_correction_request_status_shape
        CHECK (
            (status IN ('REQUESTED', 'PROCESSING')
                AND compensation_ledger_transaction_id IS NULL
                AND failure_reason IS NULL)
            OR (status = 'COMPLETED'
                AND compensation_ledger_transaction_id IS NOT NULL
                AND failure_reason IS NULL)
            OR (status = 'FAILED'
                AND compensation_ledger_transaction_id IS NULL
                AND failure_reason IS NOT NULL)
        ),
    CONSTRAINT ck_correction_request_timestamps
        CHECK (updated_at >= requested_at)
);

CREATE INDEX ix_correction_requests_case_status
    ON casework.correction_requests (tenant_id, case_id, status, updated_at);

CREATE FUNCTION casework.reject_correction_request_identity_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.correction_id IS DISTINCT FROM NEW.correction_id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.case_id IS DISTINCT FROM NEW.case_id
       OR OLD.discrepancy_id IS DISTINCT FROM NEW.discrepancy_id
       OR OLD.settlement_posting_id IS DISTINCT FROM NEW.settlement_posting_id
       OR OLD.original_ledger_transaction_id IS DISTINCT FROM NEW.original_ledger_transaction_id
       OR OLD.kind IS DISTINCT FROM NEW.kind
       OR OLD.requested_by IS DISTINCT FROM NEW.requested_by
       OR OLD.reason IS DISTINCT FROM NEW.reason
       OR OLD.requested_at IS DISTINCT FROM NEW.requested_at THEN
        RAISE EXCEPTION 'Correction request identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER correction_request_identity_immutable
BEFORE UPDATE ON casework.correction_requests
FOR EACH ROW EXECUTE FUNCTION casework.reject_correction_request_identity_mutation();
