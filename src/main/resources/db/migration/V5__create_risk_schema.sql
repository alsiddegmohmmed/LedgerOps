CREATE SCHEMA risk;

CREATE TABLE risk.risk_profiles (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    version BIGINT NOT NULL,
    review_threshold SMALLINT NOT NULL,
    reject_threshold SMALLINT NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_risk_profiles_tenant_version
        UNIQUE (tenant_id, version),

    CONSTRAINT uk_risk_profiles_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT uk_risk_profiles_tenant_id_version
        UNIQUE (tenant_id, id, version),

    CONSTRAINT ck_risk_profiles_version_positive
        CHECK (version > 0),

    CONSTRAINT ck_risk_profiles_thresholds
        CHECK (
            review_threshold >= 1
            AND review_threshold < reject_threshold
            AND reject_threshold <= 100
        )
);

CREATE UNIQUE INDEX uk_risk_profiles_one_active_per_tenant
    ON risk.risk_profiles (tenant_id)
    WHERE active;

CREATE TABLE risk.payment_amount_threshold_rules (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_threshold NUMERIC(38, 18) NOT NULL,
    score_contribution SMALLINT NOT NULL,
    enabled BOOLEAN NOT NULL,

    CONSTRAINT uk_risk_rules_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT fk_risk_rules_profile
        FOREIGN KEY (tenant_id, profile_id)
        REFERENCES risk.risk_profiles (tenant_id, id),

    CONSTRAINT ck_risk_rules_currency_format
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_risk_rules_amount_threshold_positive
        CHECK (amount_threshold > 0),

    CONSTRAINT ck_risk_rules_score_contribution
        CHECK (score_contribution BETWEEN 1 AND 100)
);

CREATE INDEX ix_risk_rules_profile
    ON risk.payment_amount_threshold_rules (tenant_id, profile_id);

CREATE TABLE risk.risk_evaluations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    profile_version BIGINT NOT NULL,
    uncapped_score BIGINT NOT NULL,
    final_score SMALLINT NOT NULL,
    decision VARCHAR(32) NOT NULL,
    evaluated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_risk_evaluations_tenant_payment
        UNIQUE (tenant_id, payment_id),

    CONSTRAINT uk_risk_evaluations_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT fk_risk_evaluations_profile_version
        FOREIGN KEY (tenant_id, profile_id, profile_version)
        REFERENCES risk.risk_profiles (tenant_id, id, version),

    CONSTRAINT ck_risk_evaluations_uncapped_score
        CHECK (uncapped_score >= 0),

    CONSTRAINT ck_risk_evaluations_final_score
        CHECK (
            final_score BETWEEN 0 AND 100
            AND final_score = LEAST(100, uncapped_score)
        ),

    CONSTRAINT ck_risk_evaluations_decision
        CHECK (decision IN ('APPROVE', 'MANUAL_REVIEW', 'REJECT'))
);

CREATE TABLE risk.evaluated_rule_results (
    tenant_id UUID NOT NULL,
    evaluation_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    rule_type VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    amount_threshold NUMERIC(38, 18) NOT NULL,
    configured_contribution SMALLINT NOT NULL,
    triggered BOOLEAN NOT NULL,
    applied_contribution SMALLINT NOT NULL,

    CONSTRAINT pk_risk_evaluated_rule_results
        PRIMARY KEY (evaluation_id, rule_id),

    CONSTRAINT fk_risk_rule_results_evaluation
        FOREIGN KEY (tenant_id, evaluation_id)
        REFERENCES risk.risk_evaluations (tenant_id, id),

    CONSTRAINT fk_risk_rule_results_rule
        FOREIGN KEY (tenant_id, rule_id)
        REFERENCES risk.payment_amount_threshold_rules (tenant_id, id),

    CONSTRAINT ck_risk_rule_results_type
        CHECK (rule_type = 'PAYMENT_AMOUNT_THRESHOLD'),

    CONSTRAINT ck_risk_rule_results_currency_format
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_risk_rule_results_amount_threshold_positive
        CHECK (amount_threshold > 0),

    CONSTRAINT ck_risk_rule_results_configured_contribution
        CHECK (configured_contribution BETWEEN 1 AND 100),

    CONSTRAINT ck_risk_rule_results_applied_contribution
        CHECK (
            (triggered AND applied_contribution = configured_contribution)
            OR (NOT triggered AND applied_contribution = 0)
        )
);

CREATE INDEX ix_risk_rule_results_tenant_evaluation
    ON risk.evaluated_rule_results (tenant_id, evaluation_id);

CREATE FUNCTION risk.protect_profile_history()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Risk profile history must not be deleted';
    END IF;

    IF NEW.id <> OLD.id
        OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.version <> OLD.version
        OR NEW.review_threshold <> OLD.review_threshold
        OR NEW.reject_threshold <> OLD.reject_threshold
        OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Versioned Risk profile content is immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE FUNCTION risk.reject_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Risk history is append-only';
END;
$$;

CREATE TRIGGER trg_risk_profiles_protect_history
    BEFORE UPDATE OR DELETE ON risk.risk_profiles
    FOR EACH ROW
    EXECUTE FUNCTION risk.protect_profile_history();

CREATE TRIGGER trg_risk_rules_append_only
    BEFORE UPDATE OR DELETE ON risk.payment_amount_threshold_rules
    FOR EACH ROW
    EXECUTE FUNCTION risk.reject_history_mutation();

CREATE TRIGGER trg_risk_evaluations_append_only
    BEFORE UPDATE OR DELETE ON risk.risk_evaluations
    FOR EACH ROW
    EXECUTE FUNCTION risk.reject_history_mutation();

CREATE TRIGGER trg_risk_rule_results_append_only
    BEFORE UPDATE OR DELETE ON risk.evaluated_rule_results
    FOR EACH ROW
    EXECUTE FUNCTION risk.reject_history_mutation();
