CREATE TABLE identity.support_sessions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    actor_issuer VARCHAR(512) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    authentication_time TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_support_sessions_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_support_sessions_actor_issuer
        CHECK (length(trim(actor_issuer)) > 0 AND actor_issuer = trim(actor_issuer)),
    CONSTRAINT ck_support_sessions_actor_subject
        CHECK (length(trim(actor_subject)) > 0 AND actor_subject = trim(actor_subject)),
    CONSTRAINT ck_support_sessions_reason
        CHECK (length(trim(reason)) > 0 AND reason = trim(reason)),
    CONSTRAINT ck_support_sessions_auth_time
        CHECK (authentication_time <= started_at),
    CONSTRAINT ck_support_sessions_expiry
        CHECK (expires_at = started_at + INTERVAL '30 minutes')
);

CREATE INDEX ix_support_sessions_active
    ON identity.support_sessions (tenant_id, expires_at, id);
