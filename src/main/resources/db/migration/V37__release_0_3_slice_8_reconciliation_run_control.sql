ALTER TABLE reconciliation.settlement_batch_families
    ADD CONSTRAINT uk_settlement_family_tenant_family UNIQUE (tenant_id, family_id);

CREATE TABLE reconciliation.batch_family_controls (
    batch_family_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_batch_family_control_family
        FOREIGN KEY (tenant_id, batch_family_id)
        REFERENCES reconciliation.settlement_batch_families (tenant_id, family_id)
);

INSERT INTO reconciliation.batch_family_controls (batch_family_id, tenant_id, created_at)
SELECT family_id, tenant_id, created_at
  FROM reconciliation.settlement_batch_families;

CREATE TABLE reconciliation.reconciliation_snapshots (
    snapshot_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    batch_family_id UUID NOT NULL,
    batch_version_id UUID NOT NULL,
    run_number INTEGER NOT NULL,
    rules_version VARCHAR(64) NOT NULL,
    source_cutoff TIMESTAMPTZ NOT NULL,
    snapshot_status VARCHAR(20) NOT NULL DEFAULT 'BUILDING',
    snapshot_sha256 CHAR(64),
    captured_record_count BIGINT NOT NULL DEFAULT 0,
    captured_fact_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    failure_reason VARCHAR(2500),
    CONSTRAINT fk_reconciliation_snapshot_family
        FOREIGN KEY (tenant_id, batch_family_id)
        REFERENCES reconciliation.settlement_batch_families (tenant_id, family_id),
    CONSTRAINT fk_reconciliation_snapshot_batch_version
        FOREIGN KEY (batch_family_id, batch_version_id)
        REFERENCES reconciliation.settlement_batch_versions (family_id, batch_version_id),
    CONSTRAINT ck_reconciliation_snapshot_run_number CHECK (run_number > 0),
    CONSTRAINT ck_reconciliation_snapshot_rules_version
        CHECK (rules_version ~ '^[!-~]{1,64}$'),
    CONSTRAINT ck_reconciliation_snapshot_hash
        CHECK (snapshot_sha256 IS NULL OR snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_reconciliation_snapshot_counts
        CHECK (captured_record_count >= 0 AND captured_fact_count >= 0),
    CONSTRAINT ck_reconciliation_snapshot_status_shape
        CHECK (
            (snapshot_status = 'BUILDING'
                AND snapshot_sha256 IS NULL AND completed_at IS NULL AND failure_reason IS NULL)
            OR (snapshot_status = 'COMPLETE'
                AND snapshot_sha256 IS NOT NULL AND completed_at IS NOT NULL AND failure_reason IS NULL)
            OR (snapshot_status = 'FAILED'
                AND completed_at IS NOT NULL AND failure_reason IS NOT NULL)
        ),
    CONSTRAINT uk_reconciliation_snapshot_family_run
        UNIQUE (tenant_id, batch_family_id, run_number),
    CONSTRAINT uk_reconciliation_snapshot_identity
        UNIQUE (tenant_id, batch_family_id, batch_version_id, snapshot_id)
);

CREATE TABLE reconciliation.reconciliation_runs (
    run_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    batch_family_id UUID NOT NULL,
    batch_version_id UUID NOT NULL,
    snapshot_id UUID NOT NULL,
    run_number INTEGER NOT NULL,
    rules_version VARCHAR(64) NOT NULL,
    source_cutoff TIMESTAMPTZ NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'QUEUED',
    matched_count BIGINT NOT NULL DEFAULT 0,
    unmatched_count BIGINT NOT NULL DEFAULT 0,
    discrepancy_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    failure_reason VARCHAR(2500),
    CONSTRAINT fk_reconciliation_run_family
        FOREIGN KEY (tenant_id, batch_family_id)
        REFERENCES reconciliation.settlement_batch_families (tenant_id, family_id),
    CONSTRAINT fk_reconciliation_run_batch_version
        FOREIGN KEY (batch_family_id, batch_version_id)
        REFERENCES reconciliation.settlement_batch_versions (family_id, batch_version_id),
    CONSTRAINT fk_reconciliation_run_snapshot
        FOREIGN KEY (tenant_id, batch_family_id, batch_version_id, snapshot_id)
        REFERENCES reconciliation.reconciliation_snapshots
            (tenant_id, batch_family_id, batch_version_id, snapshot_id),
    CONSTRAINT ck_reconciliation_run_number CHECK (run_number > 0),
    CONSTRAINT ck_reconciliation_run_rules_version
        CHECK (rules_version ~ '^[!-~]{1,64}$'),
    CONSTRAINT ck_reconciliation_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'COMPLETED',
                   'COMPLETED_WITH_DISCREPANCIES', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_reconciliation_run_counts CHECK (
        matched_count >= 0 AND unmatched_count >= 0 AND discrepancy_count >= 0
    ),
    CONSTRAINT ck_reconciliation_run_failure_reason CHECK (
        failure_reason IS NULL OR length(trim(failure_reason)) BETWEEN 1 AND 2500
    ),
    CONSTRAINT ck_reconciliation_run_status_shape CHECK (
        (status = 'QUEUED'
            AND started_at IS NULL AND terminal_at IS NULL AND failure_reason IS NULL)
        OR (status = 'RUNNING'
            AND started_at IS NOT NULL AND terminal_at IS NULL AND failure_reason IS NULL)
        OR (status = 'COMPLETED'
            AND started_at IS NOT NULL AND terminal_at IS NOT NULL
            AND failure_reason IS NULL AND discrepancy_count = 0)
        OR (status = 'COMPLETED_WITH_DISCREPANCIES'
            AND started_at IS NOT NULL AND terminal_at IS NOT NULL
            AND failure_reason IS NULL AND discrepancy_count > 0)
        OR (status = 'FAILED'
            AND started_at IS NOT NULL AND terminal_at IS NOT NULL
            AND failure_reason IS NOT NULL)
        OR (status = 'CANCELLED'
            AND terminal_at IS NOT NULL AND failure_reason IS NULL)
    ),
    CONSTRAINT uk_reconciliation_run_family_number
        UNIQUE (tenant_id, batch_family_id, run_number),
    CONSTRAINT uk_reconciliation_run_family_identity
        UNIQUE (tenant_id, batch_family_id, run_id)
);

CREATE TABLE reconciliation.current_reconciliation_runs (
    tenant_id UUID NOT NULL,
    batch_family_id UUID NOT NULL,
    run_id UUID NOT NULL,
    promoted_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_current_reconciliation_run PRIMARY KEY (tenant_id, batch_family_id),
    CONSTRAINT fk_current_reconciliation_run_family
        FOREIGN KEY (tenant_id, batch_family_id)
        REFERENCES reconciliation.settlement_batch_families (tenant_id, family_id),
    CONSTRAINT fk_current_reconciliation_run_run
        FOREIGN KEY (tenant_id, batch_family_id, run_id)
        REFERENCES reconciliation.reconciliation_runs
            (tenant_id, batch_family_id, run_id),
    CONSTRAINT uk_current_reconciliation_run_family UNIQUE (batch_family_id)
);

CREATE INDEX ix_reconciliation_runs_family_status
    ON reconciliation.reconciliation_runs (tenant_id, batch_family_id, status, run_number DESC);

CREATE INDEX ix_reconciliation_snapshots_family_created
    ON reconciliation.reconciliation_snapshots (tenant_id, batch_family_id, created_at DESC);

CREATE OR REPLACE FUNCTION reconciliation.reject_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Reconciliation snapshots cannot be deleted';
    END IF;

    IF OLD.snapshot_status <> 'BUILDING' THEN
        RAISE EXCEPTION 'Completed or failed reconciliation snapshots are immutable';
    END IF;

    IF NEW.snapshot_id <> OLD.snapshot_id
        OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.batch_family_id <> OLD.batch_family_id
        OR NEW.batch_version_id <> OLD.batch_version_id
        OR NEW.run_number <> OLD.run_number
        OR NEW.rules_version <> OLD.rules_version
        OR NEW.source_cutoff <> OLD.source_cutoff
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Reconciliation snapshot identity and source metadata are immutable';
    END IF;

    IF NEW.snapshot_status NOT IN ('COMPLETE', 'FAILED') THEN
        RAISE EXCEPTION 'A building reconciliation snapshot may only become COMPLETE or FAILED';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER reconciliation_snapshot_immutable
BEFORE UPDATE OR DELETE ON reconciliation.reconciliation_snapshots
FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_snapshot_mutation();

CREATE OR REPLACE FUNCTION reconciliation.validate_reconciliation_snapshot_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    snapshot_record RECORD;
BEGIN
    SELECT snapshot_status, tenant_id, batch_family_id, batch_version_id,
           run_number, rules_version, source_cutoff
      INTO snapshot_record
      FROM reconciliation.reconciliation_snapshots
     WHERE snapshot_id = NEW.snapshot_id
     FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Reconciliation snapshot % does not exist', NEW.snapshot_id;
    END IF;

    IF snapshot_record.snapshot_status <> 'COMPLETE' THEN
        RAISE EXCEPTION 'Reconciliation run requires a COMPLETE snapshot';
    END IF;

    IF NEW.tenant_id <> snapshot_record.tenant_id
        OR NEW.batch_family_id <> snapshot_record.batch_family_id
        OR NEW.batch_version_id <> snapshot_record.batch_version_id
        OR NEW.run_number <> snapshot_record.run_number
        OR NEW.rules_version <> snapshot_record.rules_version
        OR NEW.source_cutoff <> snapshot_record.source_cutoff THEN
        RAISE EXCEPTION 'Reconciliation run metadata must match its snapshot';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER reconciliation_run_snapshot_reference_guard
BEFORE INSERT OR UPDATE ON reconciliation.reconciliation_runs
FOR EACH ROW EXECUTE FUNCTION reconciliation.validate_reconciliation_snapshot_reference();

CREATE OR REPLACE FUNCTION reconciliation.reject_batch_family_control_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Batch family control rows are immutable lock identities';
END;
$$;

CREATE TRIGGER batch_family_control_immutable
BEFORE UPDATE OR DELETE ON reconciliation.batch_family_controls
FOR EACH ROW EXECUTE FUNCTION reconciliation.reject_batch_family_control_mutation();

CREATE OR REPLACE FUNCTION reconciliation.validate_run_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Reconciliation runs cannot be deleted';
    END IF;

    IF NEW.run_id <> OLD.run_id
        OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.batch_family_id <> OLD.batch_family_id
        OR NEW.batch_version_id <> OLD.batch_version_id
        OR NEW.snapshot_id <> OLD.snapshot_id
        OR NEW.run_number <> OLD.run_number
        OR NEW.rules_version <> OLD.rules_version
        OR NEW.source_cutoff <> OLD.source_cutoff
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Reconciliation run identity and snapshot metadata are immutable';
    END IF;

    IF NOT (
        NEW.status = OLD.status
        OR (OLD.status = 'QUEUED' AND NEW.status IN ('RUNNING', 'CANCELLED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN (
            'COMPLETED', 'COMPLETED_WITH_DISCREPANCIES', 'FAILED', 'CANCELLED'))
    ) THEN
        RAISE EXCEPTION 'Invalid Reconciliation run transition from % to %', OLD.status, NEW.status;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER reconciliation_run_mutation_guard
BEFORE UPDATE OR DELETE ON reconciliation.reconciliation_runs
FOR EACH ROW EXECUTE FUNCTION reconciliation.validate_run_mutation();

CREATE OR REPLACE FUNCTION reconciliation.validate_current_reconciliation_run()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    run_status VARCHAR(40);
BEGIN
    SELECT status
      INTO run_status
      FROM reconciliation.reconciliation_runs
     WHERE tenant_id = NEW.tenant_id
       AND batch_family_id = NEW.batch_family_id
       AND run_id = NEW.run_id
     FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Current reconciliation run does not exist';
    END IF;

    IF run_status NOT IN ('COMPLETED', 'COMPLETED_WITH_DISCREPANCIES') THEN
        RAISE EXCEPTION 'Only a completed reconciliation run can be current';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER current_reconciliation_run_guard
BEFORE INSERT OR UPDATE ON reconciliation.current_reconciliation_runs
FOR EACH ROW EXECUTE FUNCTION reconciliation.validate_current_reconciliation_run();
