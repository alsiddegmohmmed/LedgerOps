CREATE TABLE identity.service_credentials (
    id UUID PRIMARY KEY,
    application_user_id UUID NOT NULL,
    client_id VARCHAR(255) NOT NULL,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_service_credentials_application_user
        FOREIGN KEY (application_user_id) REFERENCES identity.application_users (id),
    CONSTRAINT uk_service_credentials_application_client
        UNIQUE (application_user_id, client_id),
    CONSTRAINT ck_service_credentials_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_service_credentials_client_id_not_blank
        CHECK (length(trim(client_id)) > 0)
);
