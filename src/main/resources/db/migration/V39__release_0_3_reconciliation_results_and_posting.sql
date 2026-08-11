CREATE TABLE reconciliation.snapshot_financial_subjects (
    snapshot_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    batch_version_id UUID NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    currency CHAR(3) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    provider_idempotency_key VARCHAR(120) NOT NULL,
    provider_evidence_id UUID NOT NULL,
    provider_result_id UUID NOT NULL,
    provider_reference VARCHAR(120) NOT NULL,
    provider_result_category VARCHAR(32),
    provider_observed_at TIMESTAMPTZ,
    financial_status VARCHAR(32) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    provider_evidence JSONB NOT NULL,
    ledger_transaction_id UUID,
    ledger_posted_at TIMESTAMPTZ,
    ledger_compensates_transaction_id UUID,
    ledger_total_debits NUMERIC(38, 18),
    ledger_total_credits NUMERIC(38, 18),
    ledger_entries JSONB,
    ledger_evidence JSONB NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_snapshot_financial_subject
        PRIMARY KEY (snapshot_id, subject_type, subject_id),
    CONSTRAINT fk_snapshot_financial_subject_snapshot
        FOREIGN KEY (snapshot_id, tenant_id, batch_version_id)
        REFERENCES reconciliation.reconciliation_snapshots
            (snapshot_id, tenant_id, batch_version_id),
    CONSTRAINT ck_snapshot_financial_subject_type
        CHECK (subject_type IN ('PAYMENT', 'REVERSAL')),
    CONSTRAINT ck_snapshot_financial_subject_amount
        CHECK (amount > 0),
    CONSTRAINT ck_snapshot_financial_subject_currency
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_snapshot_financial_subject_json
        CHECK (jsonb_typeof(provider_evidence) = 'object'
            AND jsonb_typeof(ledger_evidence) = 'object'
            AND (ledger_entries IS NULL OR jsonb_typeof(ledger_entries) = 'array'))
);

CREATE INDEX ix_snapshot_financial_subject_lookup
    ON reconciliation.snapshot_financial_subjects
        (tenant_id, snapshot_id, subject_type, subject_id);

CREATE OR REPLACE FUNCTION reconciliation.require_building_snapshot_financial_subject()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    snapshot_status VARCHAR(20);
BEGIN
    SELECT s.snapshot_status
      INTO snapshot_status
      FROM reconciliation.reconciliation_snapshots s
     WHERE s.snapshot_id = NEW.snapshot_id
       AND s.tenant_id = NEW.tenant_id
       AND s.batch_version_id = NEW.batch_version_id
     FOR SHARE;

    IF NOT FOUND OR snapshot_status <> 'BUILDING' THEN
        RAISE EXCEPTION 'Financial snapshot facts can only be added while snapshot is BUILDING';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER snapshot_financial_subject_build_guard
BEFORE INSERT ON reconciliation.snapshot_financial_subjects
FOR EACH ROW EXECUTE FUNCTION reconciliation.require_building_snapshot_financial_subject();

CREATE OR REPLACE FUNCTION reconciliation.reject_snapshot_financial_subject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Snapshot financial subjects are immutable';
END;
$$;

CREATE TRIGGER snapshot_financial_subject_immutable
BEFORE UPDATE OR DELETE ON reconciliation.snapshot_financial_subjects
FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_snapshot_financial_subject_mutation();

CREATE TABLE reconciliation.reconciliation_results (
    result_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    occurrence_id UUID,
    canonical_record_version_id UUID,
    subject_type VARCHAR(16),
    subject_id UUID,
    result_status VARCHAR(20) NOT NULL,
    discrepancy_category VARCHAR(64),
    provider_values JSONB NOT NULL,
    internal_values JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_reconciliation_result_run
        FOREIGN KEY (run_id) REFERENCES reconciliation.reconciliation_runs(run_id),
    CONSTRAINT fk_reconciliation_result_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES reconciliation.reconciliation_snapshots(snapshot_id),
    CONSTRAINT fk_reconciliation_result_occurrence
        FOREIGN KEY (snapshot_id, occurrence_id)
        REFERENCES reconciliation.snapshot_settlement_records(snapshot_id, occurrence_id),
    CONSTRAINT fk_reconciliation_result_canonical
        FOREIGN KEY (canonical_record_version_id)
        REFERENCES reconciliation.canonical_settlement_record_versions(canonical_record_version_id),
    CONSTRAINT ck_reconciliation_result_status
        CHECK (result_status IN ('MATCHED', 'DISCREPANCY')),
    CONSTRAINT ck_reconciliation_result_subject
        CHECK ((subject_type IS NULL AND subject_id IS NULL)
            OR (subject_type IN ('PAYMENT', 'REVERSAL') AND subject_id IS NOT NULL)),
    CONSTRAINT ck_reconciliation_result_discrepancy
        CHECK ((result_status = 'MATCHED' AND discrepancy_category IS NULL)
            OR (result_status = 'DISCREPANCY' AND discrepancy_category IS NOT NULL)),
    CONSTRAINT ck_reconciliation_result_json
        CHECK (jsonb_typeof(provider_values) = 'object'
            AND jsonb_typeof(internal_values) = 'object')
);

CREATE UNIQUE INDEX uk_reconciliation_result_run_occurrence
    ON reconciliation.reconciliation_results (run_id, occurrence_id)
    WHERE occurrence_id IS NOT NULL;

CREATE UNIQUE INDEX uk_reconciliation_result_run_subject
    ON reconciliation.reconciliation_results (run_id, subject_type, subject_id)
    WHERE subject_type IS NOT NULL AND subject_id IS NOT NULL;

CREATE INDEX ix_reconciliation_results_run_status
    ON reconciliation.reconciliation_results (tenant_id, run_id, result_status, result_id);

CREATE OR REPLACE FUNCTION reconciliation.reject_reconciliation_result_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Reconciliation results are immutable';
END;
$$;

CREATE TRIGGER reconciliation_result_immutable
BEFORE UPDATE OR DELETE ON reconciliation.reconciliation_results
FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_reconciliation_result_mutation();

CREATE TABLE reconciliation.reconciliation_subject_status_history (
    status_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id UUID NOT NULL,
    run_id UUID,
    status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reconciliation_subject_status_type
        CHECK (subject_type IN ('PAYMENT', 'REVERSAL')),
    CONSTRAINT ck_reconciliation_subject_status_value
        CHECK (status IN ('NOT_APPLICABLE', 'AWAITING_BATCH', 'PENDING',
                          'MATCHED', 'DISCREPANCY')),
    CONSTRAINT uk_reconciliation_subject_status_event
        UNIQUE (tenant_id, subject_type, subject_id, run_id, status_id)
);

CREATE TABLE reconciliation.current_reconciliation_subject_status (
    tenant_id UUID NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id UUID NOT NULL,
    run_id UUID,
    status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_current_reconciliation_subject_status
        PRIMARY KEY (tenant_id, subject_type, subject_id),
    CONSTRAINT ck_current_reconciliation_subject_status_type
        CHECK (subject_type IN ('PAYMENT', 'REVERSAL')),
    CONSTRAINT ck_current_reconciliation_subject_status_value
        CHECK (status IN ('NOT_APPLICABLE', 'AWAITING_BATCH', 'PENDING',
                          'MATCHED', 'DISCREPANCY'))
);

CREATE TABLE reconciliation.settlement_posting_instructions (
    settlement_posting_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    canonical_record_version_id UUID NOT NULL,
    occurrence_id UUID NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id UUID NOT NULL,
    template_version VARCHAR(64) NOT NULL,
    run_id UUID NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    currency CHAR(3) NOT NULL,
    instruction_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_settlement_instruction_canonical
        FOREIGN KEY (canonical_record_version_id)
        REFERENCES reconciliation.canonical_settlement_record_versions(canonical_record_version_id),
    CONSTRAINT fk_settlement_instruction_run
        FOREIGN KEY (run_id) REFERENCES reconciliation.reconciliation_runs(run_id),
    CONSTRAINT ck_settlement_instruction_subject_type
        CHECK (subject_type IN ('PAYMENT', 'REVERSAL')),
    CONSTRAINT ck_settlement_instruction_amount
        CHECK (amount > 0),
    CONSTRAINT ck_settlement_instruction_hash
        CHECK (instruction_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uk_settlement_instruction_identity
        UNIQUE (tenant_id, canonical_record_version_id, subject_type, subject_id,
                template_version)
);

CREATE TABLE reconciliation.settlement_posting_applications (
    settlement_posting_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ledger_transaction_id UUID,
    posted_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_settlement_application_instruction
        FOREIGN KEY (settlement_posting_id)
        REFERENCES reconciliation.settlement_posting_instructions(settlement_posting_id),
    CONSTRAINT ck_settlement_application_status
        CHECK (status IN ('PENDING', 'POSTED')),
    CONSTRAINT ck_settlement_application_status_shape
        CHECK ((status = 'PENDING' AND ledger_transaction_id IS NULL AND posted_at IS NULL)
            OR (status = 'POSTED' AND ledger_transaction_id IS NOT NULL AND posted_at IS NOT NULL)),
    CONSTRAINT uk_settlement_application_ledger_transaction
        UNIQUE (tenant_id, ledger_transaction_id)
);

CREATE TABLE reconciliation.settlement_posting_failures (
    failure_id UUID PRIMARY KEY,
    settlement_posting_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    safe_message VARCHAR(2500) NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_settlement_failure_application
        FOREIGN KEY (settlement_posting_id)
        REFERENCES reconciliation.settlement_posting_applications(settlement_posting_id),
    CONSTRAINT ck_settlement_failure_attempt
        CHECK (attempt_number > 0),
    CONSTRAINT uk_settlement_failure_attempt
        UNIQUE (settlement_posting_id, attempt_number)
);
