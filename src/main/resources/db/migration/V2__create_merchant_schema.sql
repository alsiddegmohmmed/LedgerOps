CREATE SCHEMA merchant;

CREATE TABLE merchant.merchants (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_merchants_tenant_name UNIQUE (tenant_id, name),

    CONSTRAINT ck_merchants_name_not_blank
        CHECK (length(trim(name)) > 0),

    CONSTRAINT ck_merchants_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE INDEX ix_merchants_tenant_id
    ON merchant.merchants (tenant_id);
