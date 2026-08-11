ALTER TABLE reconciliation.snapshot_settlement_records
    ALTER COLUMN canonical_record_version_id DROP NOT NULL,
    ALTER COLUMN normalized_content_hash DROP NOT NULL;

ALTER TABLE reconciliation.snapshot_financial_subjects
    ALTER COLUMN provider_reference DROP NOT NULL;

ALTER TABLE reconciliation.snapshot_settlement_records
    ADD COLUMN validation_state VARCHAR(24) NOT NULL DEFAULT 'VALID',
    ADD COLUMN reason_code VARCHAR(64);

ALTER TABLE reconciliation.snapshot_settlement_records
    ADD CONSTRAINT ck_snapshot_settlement_record_state
        CHECK (validation_state IN ('VALID', 'QUARANTINED')),
    ADD CONSTRAINT ck_snapshot_settlement_record_canonical_state
        CHECK ((validation_state = 'VALID' AND canonical_record_version_id IS NOT NULL
                    AND normalized_content_hash IS NOT NULL)
            OR (validation_state = 'QUARANTINED'));

DROP INDEX IF EXISTS reconciliation.uk_reconciliation_result_run_subject;
