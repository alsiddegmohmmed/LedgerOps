CREATE SCHEMA customer;

CREATE TABLE customer.customers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    customer_reference VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_customers_merchant_reference
        UNIQUE (tenant_id, merchant_id, customer_reference),

    CONSTRAINT ck_customers_reference_not_blank
        CHECK (length(trim(customer_reference)) > 0),

    CONSTRAINT ck_customers_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE INDEX ix_customers_tenant_merchant
    ON customer.customers (tenant_id, merchant_id);
