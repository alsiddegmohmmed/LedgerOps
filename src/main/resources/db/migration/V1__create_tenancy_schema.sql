CREATE SCHEMA tenancy;

CREATE TABLE tenancy.tenants (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    default_currency CHAR(3) NOT NULL,
    default_locale VARCHAR(35) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_tenants_name UNIQUE (name),

    CONSTRAINT ck_tenants_name_not_blank
        CHECK (length(trim(name)) > 0),

    CONSTRAINT ck_tenants_currency_format
        CHECK (default_currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_tenants_status
        CHECK (
            status IN (
                'PENDING_ACTIVATION',
                'ACTIVE',
                'SUSPENDED',
                'ARCHIVED'
            )
        )
);
