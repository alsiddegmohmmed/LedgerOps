CREATE SCHEMA reporting;

CREATE TABLE reporting.payment_timeline_projection (
    projection_name VARCHAR(80) NOT NULL,
    source_message_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID,
    source_module VARCHAR(80) NOT NULL,
    source_type VARCHAR(120) NOT NULL,
    source_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_source VARCHAR(120) NOT NULL,
    outcome VARCHAR(120) NOT NULL,
    reason_code VARCHAR(160),
    correlation_id UUID,
    display_text TEXT NOT NULL,

    CONSTRAINT pk_payment_timeline_projection
        PRIMARY KEY (projection_name, source_message_id),
    CONSTRAINT ck_payment_timeline_projection_name
        CHECK (length(trim(projection_name)) > 0),
    CONSTRAINT ck_payment_timeline_source_module
        CHECK (length(trim(source_module)) > 0),
    CONSTRAINT ck_payment_timeline_source_type
        CHECK (length(trim(source_type)) > 0),
    CONSTRAINT ck_payment_timeline_actor_source
        CHECK (length(trim(actor_source)) > 0),
    CONSTRAINT ck_payment_timeline_outcome
        CHECK (length(trim(outcome)) > 0),
    CONSTRAINT ck_payment_timeline_display_text
        CHECK (length(trim(display_text)) > 0)
);

CREATE INDEX ix_payment_timeline_tenant_payment_time
    ON reporting.payment_timeline_projection (tenant_id, payment_id, occurred_at, source_message_id);

CREATE INDEX ix_payment_timeline_tenant_time
    ON reporting.payment_timeline_projection (tenant_id, occurred_at, source_message_id);
