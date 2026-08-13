CREATE SEQUENCE reporting.projection_event_id_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1;

CREATE TABLE reporting.projection_event (
    event_id BIGINT NOT NULL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    generation BIGINT NOT NULL,
    merchant_id UUID,
    affected_codes VARCHAR(512) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_reporting_projection_event_generation
        FOREIGN KEY (tenant_id, generation)
        REFERENCES reporting.operational_projection_generation (tenant_id, generation),
    CONSTRAINT ck_reporting_projection_event_id_positive
        CHECK (event_id > 0),
    CONSTRAINT ck_reporting_projection_event_generation_positive
        CHECK (generation > 0),
    CONSTRAINT ck_reporting_projection_event_affected_non_blank
        CHECK (length(trim(affected_codes)) > 0)
);

CREATE INDEX ix_reporting_projection_event_tenant_cursor
    ON reporting.projection_event (tenant_id, event_id);

CREATE INDEX ix_reporting_projection_event_tenant_merchant_cursor
    ON reporting.projection_event (tenant_id, merchant_id, event_id);
