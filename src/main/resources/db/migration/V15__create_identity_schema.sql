CREATE SCHEMA identity;

CREATE TABLE identity.application_users (
    id UUID PRIMARY KEY,
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_application_users_issuer_subject
        UNIQUE (issuer, subject),

    CONSTRAINT ck_application_users_issuer_not_blank
        CHECK (length(trim(issuer)) > 0),

    CONSTRAINT ck_application_users_subject_not_blank
        CHECK (length(trim(subject)) > 0),

    CONSTRAINT ck_application_users_status
        CHECK (status IN ('ACTIVE', 'DEACTIVATED'))
);
