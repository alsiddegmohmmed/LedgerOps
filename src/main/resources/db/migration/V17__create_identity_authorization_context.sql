CREATE TABLE identity.tenant_memberships (
    id UUID PRIMARY KEY,
    application_user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_tenant_memberships_application_user
        FOREIGN KEY (application_user_id) REFERENCES identity.application_users (id),
    CONSTRAINT ck_tenant_memberships_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED'))
);

CREATE UNIQUE INDEX uk_tenant_memberships_active_identity
    ON identity.tenant_memberships (application_user_id, tenant_id)
    WHERE status <> 'REVOKED';

CREATE TABLE identity.tenant_role_assignments (
    id UUID PRIMARY KEY,
    membership_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    scope_mode VARCHAR(16) NOT NULL,

    CONSTRAINT fk_tenant_role_assignments_membership
        FOREIGN KEY (membership_id) REFERENCES identity.tenant_memberships (id),
    CONSTRAINT uk_tenant_role_assignments_membership_role
        UNIQUE (membership_id, role),
    CONSTRAINT ck_tenant_role_assignments_role
        CHECK (role IN (
            'TENANT_ADMIN', 'MERCHANT_ADMIN', 'OPERATIONS_AGENT',
            'RISK_ANALYST', 'RECONCILIATION_ANALYST', 'AUDITOR',
            'VIEWER', 'INTEGRATION_DEVELOPER'
        )),
    CONSTRAINT ck_tenant_role_assignments_scope_mode
        CHECK (scope_mode IN ('TENANT_WIDE', 'MERCHANT_SET')),
    CONSTRAINT ck_tenant_role_assignment_role_scope
        CHECK (
            (role IN ('TENANT_ADMIN', 'RECONCILIATION_ANALYST')
                AND scope_mode = 'TENANT_WIDE')
            OR (role IN ('MERCHANT_ADMIN', 'INTEGRATION_DEVELOPER')
                AND scope_mode = 'MERCHANT_SET')
            OR role IN ('OPERATIONS_AGENT', 'RISK_ANALYST', 'AUDITOR', 'VIEWER')
        )
);

CREATE TABLE identity.role_assignment_merchant_scopes (
    role_assignment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,

    CONSTRAINT pk_role_assignment_merchant_scopes
        PRIMARY KEY (role_assignment_id, merchant_id),
    CONSTRAINT fk_role_assignment_merchant_scopes_assignment
        FOREIGN KEY (role_assignment_id)
            REFERENCES identity.tenant_role_assignments (id)
);

CREATE FUNCTION identity.enforce_role_assignment_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    assignment_id UUID;
    assignment_scope VARCHAR(16);
    merchant_scope_count INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'tenant_role_assignments' THEN
        assignment_id := COALESCE(NEW.id, OLD.id);
    ELSE
        assignment_id := COALESCE(NEW.role_assignment_id, OLD.role_assignment_id);
    END IF;
    SELECT scope_mode INTO assignment_scope
      FROM identity.tenant_role_assignments
     WHERE id = assignment_id;

    IF assignment_scope IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO merchant_scope_count
      FROM identity.role_assignment_merchant_scopes
     WHERE role_assignment_id = assignment_id;

    IF assignment_scope = 'MERCHANT_SET' AND merchant_scope_count = 0 THEN
        RAISE EXCEPTION 'Merchant-scoped role assignment requires at least one Merchant';
    END IF;
    IF assignment_scope = 'TENANT_WIDE' AND merchant_scope_count <> 0 THEN
        RAISE EXCEPTION 'Tenant-wide role assignment cannot have Merchant scopes';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tenant_role_assignment_scope_complete
    AFTER INSERT OR UPDATE ON identity.tenant_role_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_role_assignment_scope();

CREATE CONSTRAINT TRIGGER merchant_scope_assignment_valid
    AFTER INSERT OR UPDATE OR DELETE ON identity.role_assignment_merchant_scopes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_role_assignment_scope();
