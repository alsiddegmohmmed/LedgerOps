CREATE SCHEMA simulator;

CREATE TABLE simulator.provider_transactions (
    transaction_id UUID PRIMARY KEY,
    provider_client_id TEXT NOT NULL,
    provider_idempotency_key TEXT NOT NULL,
    request_intent_hash CHAR(64) NOT NULL,
    request_content_hash CHAR(64) NOT NULL,
    scenario VARCHAR(32) NOT NULL,
    result_category VARCHAR(32) NOT NULL,
    provider_result_id UUID NOT NULL UNIQUE,
    provider_reference TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_simulator_idempotency
        UNIQUE (provider_client_id, provider_idempotency_key),
    CONSTRAINT ck_simulator_request_hashes CHECK (
        request_intent_hash ~ '^[0-9a-f]{64}$'
        AND request_content_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_simulator_scenario CHECK (
        scenario IN ('SUCCESS', 'DECLINE', 'ACCEPTED', 'PENDING',
                     'TEMPORARY_FAILURE', 'PERMANENT_FAILURE',
                     'TIMEOUT', 'SLOW_RESPONSE', 'TIMEOUT_THEN_SUCCESS')
    ),
    CONSTRAINT ck_simulator_result CHECK (
        result_category IN ('SUCCESS', 'ACCEPTED', 'DECLINED', 'PENDING',
                            'TEMPORARY_FAILURE', 'PERMANENT_FAILURE', 'UNKNOWN')
    )
);

CREATE TABLE simulator.scenario_overrides (
    provider_client_id TEXT NOT NULL,
    provider_idempotency_key TEXT NOT NULL,
    scenario VARCHAR(32) NOT NULL,
    PRIMARY KEY (provider_client_id, provider_idempotency_key),
    CONSTRAINT ck_simulator_override_scenario CHECK (
        scenario IN ('SUCCESS', 'DECLINE', 'ACCEPTED', 'PENDING',
                     'TEMPORARY_FAILURE', 'PERMANENT_FAILURE',
                     'TIMEOUT', 'SLOW_RESPONSE', 'TIMEOUT_THEN_SUCCESS')
    )
);
