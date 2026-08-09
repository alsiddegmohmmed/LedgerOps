-- Slice 2C evolves the dormant V18 table. Client secrets are never stored in Core.

ALTER TABLE identity.service_credentials
    DROP CONSTRAINT ck_service_credentials_status,
    ADD COLUMN label VARCHAR(255) NOT NULL DEFAULT 'legacy-credential',
    ADD COLUMN replaces_credential_id UUID,
    ADD COLUMN provisioning_operation_id UUID,
    ADD COLUMN disclosure_status VARCHAR(16) NOT NULL DEFAULT 'CONSUMED',
    ADD COLUMN disclosure_consumed_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT ck_service_credentials_status
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'FAILED', 'REVOKED')),
    ADD CONSTRAINT ck_service_credentials_label_not_blank
        CHECK (length(trim(label)) > 0),
    ADD CONSTRAINT ck_service_credentials_disclosure_status
        CHECK (disclosure_status IN ('PENDING', 'CONSUMED')),
    ADD CONSTRAINT ck_service_credentials_disclosure_evidence
        CHECK (
            (disclosure_status = 'PENDING' AND disclosure_consumed_at IS NULL)
            OR (disclosure_status = 'CONSUMED' AND disclosure_consumed_at IS NOT NULL)
        ),
    ADD CONSTRAINT fk_service_credentials_replaces
        FOREIGN KEY (replaces_credential_id) REFERENCES identity.service_credentials (id)
        ON DELETE RESTRICT;

CREATE TABLE identity.service_credential_provisioning_operations (
    id UUID PRIMARY KEY,
    credential_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    keycloak_client_id VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    failure_code VARCHAR(64),
    failure_detail VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_credential_provisioning_operations_credential
        UNIQUE (credential_id),
    CONSTRAINT fk_credential_provisioning_operations_credential
        FOREIGN KEY (credential_id) REFERENCES identity.service_credentials (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_credential_provisioning_operations_client_id_not_blank
        CHECK (length(trim(keycloak_client_id)) > 0),
    CONSTRAINT ck_credential_provisioning_operations_status
        CHECK (status IN ('PENDING', 'FAILED', 'COMPLETED', 'REVOKED')),
    CONSTRAINT ck_credential_provisioning_operations_attempt_count
        CHECK (attempt_count >= 0),
    CONSTRAINT ck_credential_provisioning_operations_failure_evidence
        CHECK (
            (status = 'FAILED' AND failure_code IS NOT NULL)
            OR (status <> 'FAILED' AND failure_code IS NULL AND failure_detail IS NULL)
        ),
    CONSTRAINT ck_credential_provisioning_operations_updated_at
        CHECK (updated_at >= created_at)
);

-- V18 was dormant, but any legacy rows still receive durable operation evidence
-- before the relationship is made mandatory.
INSERT INTO identity.service_credential_provisioning_operations (
    id, credential_id, tenant_id, keycloak_client_id, status,
    attempt_count, created_at, updated_at
)
SELECT id, id, tenant_id, client_id, 'COMPLETED', 0, created_at, updated_at
  FROM identity.service_credentials;

UPDATE identity.service_credentials
   SET provisioning_operation_id = id
 WHERE provisioning_operation_id IS NULL;

ALTER TABLE identity.service_credentials
    ADD CONSTRAINT fk_service_credentials_provisioning_operation
        FOREIGN KEY (provisioning_operation_id)
        REFERENCES identity.service_credential_provisioning_operations (id)
        ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    ALTER COLUMN provisioning_operation_id SET NOT NULL;

CREATE INDEX ix_service_credentials_tenant_merchant_status
    ON identity.service_credentials (tenant_id, merchant_id, status);

CREATE INDEX ix_credential_provisioning_operations_pending
    ON identity.service_credential_provisioning_operations (status, updated_at)
    WHERE status IN ('PENDING', 'FAILED');
