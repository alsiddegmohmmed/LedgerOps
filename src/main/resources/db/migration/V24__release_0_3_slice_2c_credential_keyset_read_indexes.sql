CREATE INDEX ix_service_credentials_tenant_created_id
    ON identity.service_credentials (tenant_id, created_at DESC, id DESC);

CREATE INDEX ix_service_credentials_tenant_merchant_created_id
    ON identity.service_credentials (tenant_id, merchant_id, created_at DESC, id DESC);
