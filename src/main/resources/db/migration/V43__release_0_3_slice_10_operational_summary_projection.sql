CREATE TABLE reporting.operational_projection_generation (
    tenant_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    cursor BIGINT NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_operational_projection_generation
        PRIMARY KEY (tenant_id, generation),
    CONSTRAINT ck_operational_projection_generation_positive
        CHECK (generation > 0),
    CONSTRAINT ck_operational_projection_cursor_non_negative
        CHECK (cursor >= 0)
);

CREATE TABLE reporting.operational_projection_current (
    tenant_id UUID NOT NULL PRIMARY KEY,
    generation BIGINT NOT NULL,
    cursor BIGINT NOT NULL,
    as_of TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_operational_projection_current_generation
        FOREIGN KEY (tenant_id, generation)
        REFERENCES reporting.operational_projection_generation (tenant_id, generation),
    CONSTRAINT ck_operational_projection_current_cursor_non_negative
        CHECK (cursor >= 0)
);

CREATE TABLE reporting.operational_summary_fact (
    tenant_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    metric_code VARCHAR(64) NOT NULL,
    source_type VARCHAR(100) NOT NULL,
    source_id UUID NOT NULL,
    merchant_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    amount NUMERIC(19, 4),
    currency VARCHAR(3),
    value_code VARCHAR(80),
    current_state VARCHAR(80),
    current_reconciliation_run BOOLEAN,

    CONSTRAINT pk_operational_summary_fact
        PRIMARY KEY (tenant_id, generation, metric_code, source_id),
    CONSTRAINT fk_operational_summary_fact_generation
        FOREIGN KEY (tenant_id, generation)
        REFERENCES reporting.operational_projection_generation (tenant_id, generation),
    CONSTRAINT ck_operational_summary_fact_metric
        CHECK (metric_code IN (
            'PAYMENT_VOLUME', 'PAYMENT_SUCCESS', 'PAYMENT_FAILURE',
            'PAYMENT_PROVIDER_TERMINAL', 'MANUAL_REVIEW', 'OPEN_DISCREPANCY',
            'UNRESOLVED_CASE', 'PROVIDER_HEALTH_EVALUATION'
        )),
    CONSTRAINT ck_operational_summary_fact_source_type
        CHECK (length(trim(source_type)) > 0),
    CONSTRAINT ck_operational_summary_fact_amount
        CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT ck_operational_summary_fact_currency
        CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_operational_summary_fact_current_run
        CHECK (metric_code <> 'OPEN_DISCREPANCY'
            OR current_reconciliation_run IS NOT NULL)
);

CREATE INDEX ix_operational_summary_fact_query
    ON reporting.operational_summary_fact
        (tenant_id, generation, metric_code, occurred_at DESC, source_id DESC);

CREATE INDEX ix_operational_summary_fact_merchant_query
    ON reporting.operational_summary_fact
        (tenant_id, generation, metric_code, merchant_id, occurred_at DESC, source_id DESC);
