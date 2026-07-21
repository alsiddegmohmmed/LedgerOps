ALTER TABLE provider.work
    ADD COLUMN execution_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_request_id UUID,
    ADD COLUMN last_error_code TEXT,
    ADD CONSTRAINT ck_provider_work_execution_count CHECK (
        execution_count BETWEEN 0 AND 12
        AND (work_type <> 'SUBMISSION' OR execution_count <= 1)
    );

ALTER TABLE provider.work
    ADD CONSTRAINT uk_provider_work_id_tenant UNIQUE (id, tenant_id);

CREATE TABLE provider.interactions (
    interaction_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    work_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    work_type VARCHAR(32) NOT NULL,
    request_id UUID NOT NULL,
    request_body_hash CHAR(64) NOT NULL,
    response_body_hash CHAR(64),
    http_status INTEGER,
    communication_outcome VARCHAR(32) NOT NULL,
    latency_millis BIGINT NOT NULL,
    safe_error_code TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_provider_interaction_request UNIQUE (tenant_id, provider_id, request_id),
    CONSTRAINT uk_provider_interaction_id_tenant UNIQUE (interaction_id, tenant_id),
    CONSTRAINT fk_provider_interaction_work FOREIGN KEY (work_id, tenant_id)
        REFERENCES provider.work (id, tenant_id),
    CONSTRAINT ck_provider_interaction_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_interaction_work_type CHECK (work_type IN ('SUBMISSION', 'STATUS_QUERY')),
    CONSTRAINT ck_provider_interaction_outcome CHECK (
        communication_outcome IN ('RESPONSE', 'TIMEOUT', 'CONNECTION_FAILURE', 'CIRCUIT_OPEN', 'BULKHEAD_FULL')
    ),
    CONSTRAINT ck_provider_interaction_hashes CHECK (
        request_body_hash ~ '^[0-9a-f]{64}$'
        AND (response_body_hash IS NULL OR response_body_hash ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_provider_interaction_latency CHECK (latency_millis >= 0)
);

CREATE TABLE provider.results (
    evidence_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    interaction_id UUID NOT NULL UNIQUE,
    work_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    provider_idempotency_key TEXT NOT NULL,
    provider_result_id UUID NOT NULL,
    provider_reference TEXT,
    result_category VARCHAR(32) NOT NULL,
    retry_disposition VARCHAR(32) NOT NULL,
    provider_transaction_found BOOLEAN NOT NULL,
    no_acceptance_proven BOOLEAN NOT NULL,
    evidence_origin VARCHAR(32) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_provider_result UNIQUE (tenant_id, provider_id, provider_result_id),
    CONSTRAINT fk_provider_result_interaction FOREIGN KEY (interaction_id, tenant_id)
        REFERENCES provider.interactions (interaction_id, tenant_id),
    CONSTRAINT fk_provider_result_work FOREIGN KEY (work_id, tenant_id)
        REFERENCES provider.work (id, tenant_id),
    CONSTRAINT ck_provider_result_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_result_origin CHECK (
        evidence_origin IN ('SUBMISSION_RESPONSE', 'STATUS_QUERY', 'WEBHOOK')
    ),
    CONSTRAINT ck_provider_result_category CHECK (
        result_category IN ('SUCCESS', 'ACCEPTED', 'DECLINED', 'PENDING',
                            'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'UNKNOWN')
    ),
    CONSTRAINT ck_provider_retry_disposition CHECK (
        retry_disposition IN ('SAFE_TO_RESUBMIT', 'STATUS_RECOVERY_REQUIRED', 'NOT_RETRYABLE')
    ),
    CONSTRAINT ck_provider_result_disposition_matrix CHECK (
        (result_category IN ('SUCCESS', 'DECLINED', 'PERMANENT_FAILURE')
            AND retry_disposition = 'NOT_RETRYABLE')
        OR (result_category IN ('ACCEPTED', 'PENDING', 'UNKNOWN')
            AND retry_disposition = 'STATUS_RECOVERY_REQUIRED')
        OR (result_category = 'TEMPORARY_FAILURE'
            AND retry_disposition IN ('SAFE_TO_RESUBMIT', 'STATUS_RECOVERY_REQUIRED'))
    ),
    CONSTRAINT ck_provider_safe_retry CHECK (
        retry_disposition <> 'SAFE_TO_RESUBMIT'
        OR (result_category = 'TEMPORARY_FAILURE'
            AND no_acceptance_proven
            AND NOT provider_transaction_found)
    ),
    CONSTRAINT ck_provider_status_found_not_safe CHECK (
        NOT provider_transaction_found OR retry_disposition <> 'SAFE_TO_RESUBMIT'
    ),
    CONSTRAINT ck_provider_no_acceptance_scope CHECK (
        NOT no_acceptance_proven
        OR (result_category = 'TEMPORARY_FAILURE'
            AND retry_disposition = 'SAFE_TO_RESUBMIT')
    )
);

CREATE TABLE provider.operational_events (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    work_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    safe_reason_code TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_provider_operational_event_work FOREIGN KEY (work_id, tenant_id)
        REFERENCES provider.work (id, tenant_id),
    CONSTRAINT ck_provider_operational_event_type CHECK (
        event_type IN ('SUBMISSION_OUTCOME_AMBIGUOUS')
    )
);

CREATE FUNCTION provider.reject_interaction_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Provider interaction evidence is immutable';
END;
$$;

CREATE TRIGGER provider_interaction_immutable
BEFORE UPDATE OR DELETE ON provider.interactions
FOR EACH ROW EXECUTE FUNCTION provider.reject_interaction_mutation();

CREATE TRIGGER provider_result_immutable
BEFORE UPDATE OR DELETE ON provider.results
FOR EACH ROW EXECUTE FUNCTION provider.reject_interaction_mutation();

CREATE TRIGGER provider_operational_event_immutable
BEFORE UPDATE OR DELETE ON provider.operational_events
FOR EACH ROW EXECUTE FUNCTION provider.reject_interaction_mutation();

CREATE TRIGGER provider_work_delete_prohibited
BEFORE DELETE ON provider.work
FOR EACH ROW EXECUTE FUNCTION provider.reject_interaction_mutation();
