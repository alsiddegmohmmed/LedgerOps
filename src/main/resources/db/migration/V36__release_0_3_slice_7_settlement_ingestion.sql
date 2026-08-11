CREATE TABLE BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_NAME VARCHAR(100) NOT NULL,
    JOB_KEY VARCHAR(32) NOT NULL,
    CONSTRAINT JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
);

CREATE TABLE BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_INSTANCE_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
        REFERENCES BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT NOT NULL,
    PARAMETER_NAME VARCHAR(100) NOT NULL,
    PARAMETER_TYPE VARCHAR(100) NOT NULL,
    PARAMETER_VALUE VARCHAR(2500),
    IDENTIFYING CHAR(1) NOT NULL,
    CONSTRAINT JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    VERSION BIGINT NOT NULL,
    STEP_NAME VARCHAR(100) NOT NULL,
    JOB_EXECUTION_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    COMMIT_COUNT BIGINT,
    READ_COUNT BIGINT,
    FILTER_COUNT BIGINT,
    WRITE_COUNT BIGINT,
    READ_SKIP_COUNT BIGINT,
    WRITE_SKIP_COUNT BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT BIGINT,
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    CONSTRAINT JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE TABLE BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
        REFERENCES BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
);

CREATE TABLE BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    CONSTRAINT JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
        REFERENCES BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
);

CREATE SEQUENCE BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;

CREATE SCHEMA reconciliation;

CREATE TABLE reconciliation.settlement_batch_families (
    family_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    provider_batch_reference VARCHAR(100) NOT NULL,
    settlement_period_start DATE NOT NULL,
    settlement_period_end DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_settlement_family_period CHECK (settlement_period_end >= settlement_period_start),
    CONSTRAINT uk_settlement_family_identity UNIQUE (
        tenant_id, provider_id, provider_batch_reference,
        settlement_period_start, settlement_period_end
    )
);

CREATE TABLE reconciliation.settlement_batch_versions (
    batch_version_id UUID PRIMARY KEY,
    family_id UUID NOT NULL REFERENCES reconciliation.settlement_batch_families(family_id),
    raw_file_sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    byte_size BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    supersedes_batch_version_id UUID NULL
        REFERENCES reconciliation.settlement_batch_versions(batch_version_id),
    total_rows BIGINT NOT NULL DEFAULT 0,
    valid_rows BIGINT NOT NULL DEFAULT 0,
    invalid_rows BIGINT NOT NULL DEFAULT 0,
    structural_error_code VARCHAR(64),
    created_by_application_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_settlement_batch_version_hash CHECK (raw_file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_settlement_batch_version_size CHECK (byte_size >= 0),
    CONSTRAINT ck_settlement_batch_status CHECK (
        status IN ('RECEIVED', 'VALIDATING', 'READY', 'PROCESSING',
                   'COMPLETED', 'COMPLETED_WITH_DISCREPANCIES', 'FAILED')
    ),
    CONSTRAINT ck_settlement_batch_counts CHECK (
        total_rows >= 0 AND valid_rows >= 0 AND invalid_rows >= 0
        AND valid_rows + invalid_rows <= total_rows
    ),
    CONSTRAINT uk_settlement_batch_content UNIQUE (family_id, raw_file_sha256)
);

ALTER TABLE reconciliation.settlement_batch_versions
    ADD CONSTRAINT uk_settlement_batch_family_version UNIQUE (family_id, batch_version_id);

ALTER TABLE reconciliation.settlement_batch_versions
    ADD CONSTRAINT fk_settlement_batch_supersedes_same_family
    FOREIGN KEY (family_id, supersedes_batch_version_id)
    REFERENCES reconciliation.settlement_batch_versions(family_id, batch_version_id);

CREATE TABLE reconciliation.settlement_validation_items (
    validation_item_id UUID PRIMARY KEY,
    batch_version_id UUID NOT NULL REFERENCES reconciliation.settlement_batch_versions(batch_version_id),
    row_number BIGINT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    safe_evidence JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_settlement_validation_row CHECK (row_number > 0),
    CONSTRAINT ck_settlement_validation_evidence CHECK (jsonb_typeof(safe_evidence) = 'object'),
    CONSTRAINT uk_settlement_validation_row_reason UNIQUE (batch_version_id, row_number, reason_code)
);

CREATE TABLE reconciliation.settlement_record_occurrences (
    occurrence_id UUID PRIMARY KEY,
    batch_version_id UUID NOT NULL REFERENCES reconciliation.settlement_batch_versions(batch_version_id),
    tenant_id UUID NOT NULL,
    row_number BIGINT NOT NULL,
    provider_record_key VARCHAR(120) NOT NULL,
    normalized_content_hash CHAR(64),
    normalized_content JSONB NOT NULL,
    validation_state VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64),
    canonical_record_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_settlement_occurrence_row CHECK (row_number > 0),
    CONSTRAINT ck_settlement_occurrence_hash CHECK (
        normalized_content_hash IS NULL OR normalized_content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_settlement_occurrence_content CHECK (jsonb_typeof(normalized_content) = 'object'),
    CONSTRAINT ck_settlement_occurrence_state CHECK (validation_state IN ('VALID', 'QUARANTINED')),
    CONSTRAINT uk_settlement_occurrence_position UNIQUE (batch_version_id, row_number)
);

CREATE TABLE reconciliation.canonical_settlement_record_versions (
    canonical_record_version_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    provider_record_key VARCHAR(120) NOT NULL,
    normalized_content_hash CHAR(64) NOT NULL,
    normalized_content JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_settlement_canonical_hash CHECK (normalized_content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_settlement_canonical_content CHECK (jsonb_typeof(normalized_content) = 'object'),
    CONSTRAINT uk_settlement_canonical_identity UNIQUE (
        tenant_id, provider_id, provider_record_key, normalized_content_hash
    )
);

ALTER TABLE reconciliation.settlement_record_occurrences
    ADD CONSTRAINT fk_settlement_occurrence_canonical
    FOREIGN KEY (canonical_record_version_id)
    REFERENCES reconciliation.canonical_settlement_record_versions(canonical_record_version_id);

CREATE INDEX ix_settlement_batch_versions_tenant_created
    ON reconciliation.settlement_batch_versions(family_id, created_at DESC);
CREATE INDEX ix_settlement_validation_items_batch_row
    ON reconciliation.settlement_validation_items(batch_version_id, row_number);
CREATE INDEX ix_settlement_occurrences_batch_state
    ON reconciliation.settlement_record_occurrences(batch_version_id, validation_state, row_number);
CREATE INDEX ix_settlement_canonical_record_key
    ON reconciliation.canonical_settlement_record_versions(tenant_id, provider_id, provider_record_key);
