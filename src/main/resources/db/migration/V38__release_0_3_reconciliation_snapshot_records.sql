ALTER TABLE reconciliation.settlement_record_occurrences
    ADD CONSTRAINT uk_settlement_occurrence_tenant_batch_id
        UNIQUE (tenant_id, batch_version_id, occurrence_id);

ALTER TABLE reconciliation.reconciliation_snapshots
    ADD CONSTRAINT uk_reconciliation_snapshot_tenant_batch
        UNIQUE (snapshot_id, tenant_id, batch_version_id);

CREATE TABLE reconciliation.snapshot_settlement_records (
    snapshot_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    batch_version_id UUID NOT NULL,
    occurrence_id UUID NOT NULL,
    canonical_record_version_id UUID NOT NULL,
    row_number BIGINT NOT NULL,
    provider_record_key VARCHAR(120) NOT NULL,
    normalized_content_hash CHAR(64) NOT NULL,
    normalized_content JSONB NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_snapshot_settlement_record PRIMARY KEY (snapshot_id, occurrence_id),
    CONSTRAINT fk_snapshot_settlement_record_snapshot
        FOREIGN KEY (snapshot_id, tenant_id, batch_version_id)
        REFERENCES reconciliation.reconciliation_snapshots
            (snapshot_id, tenant_id, batch_version_id),
    CONSTRAINT fk_snapshot_settlement_record_occurrence
        FOREIGN KEY (tenant_id, batch_version_id, occurrence_id)
        REFERENCES reconciliation.settlement_record_occurrences
            (tenant_id, batch_version_id, occurrence_id),
    CONSTRAINT fk_snapshot_settlement_record_canonical
        FOREIGN KEY (canonical_record_version_id)
        REFERENCES reconciliation.canonical_settlement_record_versions
            (canonical_record_version_id),
    CONSTRAINT ck_snapshot_settlement_record_row CHECK (row_number > 0),
    CONSTRAINT ck_snapshot_settlement_record_hash
        CHECK (normalized_content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_snapshot_settlement_record_content
        CHECK (jsonb_typeof(normalized_content) = 'object'),
    CONSTRAINT uk_snapshot_settlement_record_row UNIQUE (snapshot_id, row_number)
);

CREATE INDEX ix_snapshot_settlement_record_lookup
    ON reconciliation.snapshot_settlement_records
        (tenant_id, snapshot_id, provider_record_key, row_number);

CREATE OR REPLACE FUNCTION reconciliation.reject_snapshot_settlement_record_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Snapshot settlement records are immutable';
END;
$$;

CREATE TRIGGER snapshot_settlement_record_immutable
BEFORE UPDATE OR DELETE ON reconciliation.snapshot_settlement_records
FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_snapshot_settlement_record_mutation();

CREATE OR REPLACE FUNCTION reconciliation.require_building_reconciliation_snapshot()
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

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Snapshot does not exist for settlement record';
    END IF;

    IF snapshot_status <> 'BUILDING' THEN
        RAISE EXCEPTION 'Snapshot settlement records can only be added while snapshot is BUILDING';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER snapshot_settlement_record_build_guard
BEFORE INSERT ON reconciliation.snapshot_settlement_records
FOR EACH ROW EXECUTE FUNCTION reconciliation.require_building_reconciliation_snapshot();
