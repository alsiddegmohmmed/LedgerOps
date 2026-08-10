CREATE TABLE provider.scenario_profiles (
    profile_id UUID NOT NULL,
    version BIGINT NOT NULL,
    submission_outcome VARCHAR(64) NOT NULL,
    webhook_mode VARCHAR(32) NOT NULL,
    settlement_mode VARCHAR(32) NOT NULL,
    delay_millis BIGINT NOT NULL DEFAULT 0,
    fixture_id VARCHAR(128),
    parameters JSONB NOT NULL,
    canonical_snapshot JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (profile_id, version),
    CONSTRAINT ck_provider_scenario_version CHECK (version > 0),
    CONSTRAINT ck_provider_scenario_delay CHECK (delay_millis BETWEEN 0 AND 300000),
    CONSTRAINT ck_provider_scenario_parameters CHECK (jsonb_typeof(parameters) = 'object'),
    CONSTRAINT ck_provider_scenario_snapshot CHECK (jsonb_typeof(canonical_snapshot) = 'object'),
    CONSTRAINT ck_provider_scenario_submission_outcome CHECK (
        submission_outcome IN (
            'SUCCESS', 'DECLINE', 'ACCEPTED', 'PENDING',
            'TIMEOUT_AFTER_ACCEPTANCE',
            'TEMPORARY_FAILURE_SAFE_TO_RESUBMIT',
            'TEMPORARY_FAILURE_STATUS_RECOVERY'
        )
    ),
    CONSTRAINT ck_provider_scenario_webhook_mode CHECK (
        webhook_mode IN ('NORMAL', 'DELAYED', 'DUPLICATE', 'MISSING', 'INVALID_SIGNATURE', 'OUT_OF_ORDER')
    ),
    CONSTRAINT ck_provider_scenario_settlement_mode CHECK (
        settlement_mode IN ('EXACT', 'MISSING', 'AMOUNT_MISMATCH', 'CURRENCY_MISMATCH',
                            'STATUS_MISMATCH', 'DUPLICATE_RECORD', 'DATE_MISMATCH')
    )
);

CREATE TABLE provider.scenario_assignments (
    assignment_id UUID PRIMARY KEY,
    scope VARCHAR(16) NOT NULL,
    tenant_id UUID,
    payment_id UUID,
    profile_id UUID NOT NULL,
    profile_version BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_provider_assignment_profile
        FOREIGN KEY (profile_id, profile_version)
        REFERENCES provider.scenario_profiles (profile_id, version),
    CONSTRAINT ck_provider_assignment_scope CHECK (scope IN ('GLOBAL', 'TENANT', 'PAYMENT')),
    CONSTRAINT ck_provider_assignment_target CHECK (
        (scope = 'GLOBAL' AND tenant_id IS NULL AND payment_id IS NULL)
        OR (scope = 'TENANT' AND tenant_id IS NOT NULL AND payment_id IS NULL)
        OR (scope = 'PAYMENT' AND tenant_id IS NOT NULL AND payment_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_provider_active_global_scenario
    ON provider.scenario_assignments ((scope))
    WHERE active AND scope = 'GLOBAL';

CREATE UNIQUE INDEX uk_provider_active_tenant_scenario
    ON provider.scenario_assignments (tenant_id)
    WHERE active AND scope = 'TENANT';

CREATE UNIQUE INDEX uk_provider_active_payment_scenario
    ON provider.scenario_assignments (tenant_id, payment_id)
    WHERE active AND scope = 'PAYMENT';

CREATE TABLE provider.scenario_pins (
    tenant_id UUID NOT NULL,
    operation_type VARCHAR(16) NOT NULL,
    operation_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    profile_version BIGINT NOT NULL,
    canonical_snapshot JSONB NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, operation_type, operation_id),
    CONSTRAINT fk_provider_scenario_pin_profile
        FOREIGN KEY (profile_id, profile_version)
        REFERENCES provider.scenario_profiles (profile_id, version),
    CONSTRAINT ck_provider_scenario_pin_type CHECK (operation_type IN ('PAYMENT', 'REVERSAL')),
    CONSTRAINT ck_provider_scenario_pin_snapshot CHECK (jsonb_typeof(canonical_snapshot) = 'object')
);

ALTER TABLE provider.work
    ADD COLUMN scenario_profile_id UUID,
    ADD COLUMN scenario_profile_version BIGINT,
    ADD COLUMN scenario_snapshot JSONB,
    ADD CONSTRAINT fk_provider_work_scenario_profile
        FOREIGN KEY (scenario_profile_id, scenario_profile_version)
        REFERENCES provider.scenario_profiles (profile_id, version),
    ADD CONSTRAINT ck_provider_work_scenario_shape CHECK (
        (scenario_profile_id IS NULL AND scenario_profile_version IS NULL AND scenario_snapshot IS NULL)
        OR (scenario_profile_id IS NOT NULL AND scenario_profile_version IS NOT NULL
            AND scenario_snapshot IS NOT NULL AND jsonb_typeof(scenario_snapshot) = 'object')
    );

CREATE FUNCTION provider.reject_scenario_profile_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Provider scenario profiles are immutable';
END;
$$;

CREATE TRIGGER provider_scenario_profile_immutable
BEFORE UPDATE OR DELETE ON provider.scenario_profiles
FOR EACH ROW EXECUTE FUNCTION provider.reject_scenario_profile_mutation();

CREATE FUNCTION provider.reject_scenario_assignment_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.assignment_id IS DISTINCT FROM OLD.assignment_id
       OR NEW.scope IS DISTINCT FROM OLD.scope
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.payment_id IS DISTINCT FROM OLD.payment_id
       OR NEW.profile_id IS DISTINCT FROM OLD.profile_id
       OR NEW.profile_version IS DISTINCT FROM OLD.profile_version
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Provider scenario assignment identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER provider_scenario_assignment_business_immutable
BEFORE UPDATE ON provider.scenario_assignments
FOR EACH ROW EXECUTE FUNCTION provider.reject_scenario_assignment_mutation();

CREATE TRIGGER provider_scenario_assignment_delete_prohibited
BEFORE DELETE ON provider.scenario_assignments
FOR EACH ROW EXECUTE FUNCTION provider.reject_scenario_assignment_mutation();

CREATE TABLE provider.health_policies (
    policy_id UUID NOT NULL,
    provider_id VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    window_seconds INTEGER NOT NULL,
    evaluation_interval_seconds INTEGER NOT NULL,
    minimum_completed_calls INTEGER NOT NULL,
    degraded_error_rate NUMERIC(8, 6) NOT NULL,
    degraded_p95_latency_millis BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (policy_id, version),
    CONSTRAINT ck_provider_health_policy_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_health_policy_values CHECK (
        window_seconds > 0 AND evaluation_interval_seconds > 0
        AND minimum_completed_calls > 0
        AND degraded_error_rate BETWEEN 0 AND 1
        AND degraded_p95_latency_millis > 0
    )
);

CREATE UNIQUE INDEX uk_provider_active_health_policy
    ON provider.health_policies (provider_id)
    WHERE active;

CREATE TABLE provider.health_evaluations (
    evaluation_id UUID PRIMARY KEY,
    provider_id VARCHAR(32) NOT NULL,
    policy_id UUID NOT NULL,
    policy_version BIGINT NOT NULL,
    health_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    completed_calls INTEGER NOT NULL,
    successful_communications INTEGER NOT NULL,
    timeout_count INTEGER NOT NULL,
    system_error_count INTEGER NOT NULL,
    p95_latency_millis BIGINT NOT NULL,
    circuit_state VARCHAR(16) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    window_ended_at TIMESTAMPTZ NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_provider_health_evaluation_policy
        FOREIGN KEY (policy_id, policy_version)
        REFERENCES provider.health_policies (policy_id, version),
    CONSTRAINT ck_provider_health_evaluation_provider CHECK (provider_id = 'SIMULATOR'),
    CONSTRAINT ck_provider_health_evaluation_state CHECK (
        state IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'UNAVAILABLE')
    ),
    CONSTRAINT ck_provider_health_evaluation_counts CHECK (
        completed_calls >= 0 AND successful_communications >= 0
        AND timeout_count >= 0 AND system_error_count >= 0
        AND p95_latency_millis >= 0
    )
);

CREATE TABLE provider.health_current (
    provider_id VARCHAR(32) PRIMARY KEY,
    evaluation_id UUID NOT NULL,
    health_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_provider_health_current_evaluation
        FOREIGN KEY (evaluation_id) REFERENCES provider.health_evaluations (evaluation_id),
    CONSTRAINT ck_provider_health_current_state CHECK (
        state IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'UNAVAILABLE')
    )
);

CREATE INDEX ix_provider_health_evaluation_recent
    ON provider.health_evaluations (provider_id, evaluated_at DESC);

CREATE FUNCTION provider.reject_health_evaluation_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Provider health evaluations are append-only';
END;
$$;

CREATE TRIGGER provider_health_evaluation_immutable
BEFORE UPDATE OR DELETE ON provider.health_evaluations
FOR EACH ROW EXECUTE FUNCTION provider.reject_health_evaluation_mutation();

INSERT INTO provider.health_policies (
    policy_id, provider_id, version, window_seconds, evaluation_interval_seconds,
    minimum_completed_calls, degraded_error_rate, degraded_p95_latency_millis,
    active, created_at
) VALUES (
    '00000000-0000-0000-0000-000000000027', 'SIMULATOR', 1, 300, 30,
    10, 0.200000, 3000, true, TIMESTAMPTZ '2026-01-01T00:00:00Z'
);

INSERT INTO provider.scenario_profiles (
    profile_id, version, submission_outcome, webhook_mode, settlement_mode,
    delay_millis, fixture_id, parameters, canonical_snapshot, created_at
) VALUES (
    '00000000-0000-0000-0000-000000000005', 1, 'SUCCESS', 'NORMAL', 'EXACT',
    0, NULL, '{}'::jsonb,
    '{"profileId":"00000000-0000-0000-0000-000000000005","version":1,"submissionOutcome":"SUCCESS","webhookMode":"NORMAL","settlementMode":"EXACT","delayMillis":0,"fixtureId":null,"parameters":{}}'::jsonb,
    TIMESTAMPTZ '2026-01-01T00:00:00Z'
);
