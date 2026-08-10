ALTER TABLE simulator.provider_transactions
    ADD COLUMN scenario_profile_id UUID,
    ADD COLUMN scenario_profile_version BIGINT,
    ADD COLUMN scenario_snapshot JSONB,
    ADD CONSTRAINT ck_simulator_scenario_snapshot_shape CHECK (
        (scenario_profile_id IS NULL AND scenario_profile_version IS NULL AND scenario_snapshot IS NULL)
        OR (scenario_profile_id IS NOT NULL AND scenario_profile_version IS NOT NULL
            AND scenario_profile_version > 0 AND scenario_snapshot IS NOT NULL
            AND jsonb_typeof(scenario_snapshot) = 'object')
    );
