CREATE FUNCTION identity.enforce_merchant_scope_tenant_ownership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    assignment_id UUID;
    membership_tenant_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'tenant_role_assignments' THEN
        assignment_id := COALESCE(NEW.id, OLD.id);
    ELSE
        assignment_id := COALESCE(NEW.role_assignment_id, OLD.role_assignment_id);
    END IF;

    SELECT membership.tenant_id
      INTO membership_tenant_id
      FROM identity.tenant_role_assignments assignment
      JOIN identity.tenant_memberships membership
        ON membership.id = assignment.membership_id
     WHERE assignment.id = assignment_id;

    IF membership_tenant_id IS NULL THEN
        RETURN NULL;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM identity.role_assignment_merchant_scopes scope
          LEFT JOIN merchant.merchants merchant
            ON merchant.id = scope.merchant_id
         WHERE scope.role_assignment_id = assignment_id
           AND (merchant.id IS NULL OR merchant.tenant_id <> membership_tenant_id)
    ) THEN
        RAISE EXCEPTION 'Merchant scope must belong to the membership Tenant';
    END IF;

    RETURN NULL;
END;
$$;

ALTER TABLE identity.role_assignment_merchant_scopes
    ADD CONSTRAINT fk_role_assignment_merchant_scopes_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant.merchants (id) ON DELETE RESTRICT;

CREATE CONSTRAINT TRIGGER role_assignment_merchant_scope_tenant_ownership
    AFTER INSERT OR UPDATE ON identity.tenant_role_assignments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_merchant_scope_tenant_ownership();

CREATE CONSTRAINT TRIGGER merchant_scope_tenant_ownership
    AFTER INSERT OR UPDATE OR DELETE ON identity.role_assignment_merchant_scopes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_merchant_scope_tenant_ownership();

CREATE FUNCTION identity.reject_membership_tenant_change_with_scopes()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM identity.role_assignment_merchant_scopes scope
          JOIN identity.tenant_role_assignments assignment
            ON assignment.id = scope.role_assignment_id
          JOIN merchant.merchants merchant
            ON merchant.id = scope.merchant_id
         WHERE assignment.membership_id = NEW.id
           AND merchant.tenant_id <> NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'Membership cannot move across Tenant boundaries while Merchant scopes exist';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER membership_tenant_change_with_scopes
    AFTER UPDATE OF tenant_id ON identity.tenant_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.reject_membership_tenant_change_with_scopes();

CREATE FUNCTION identity.reject_merchant_tenant_change_with_scopes()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM identity.role_assignment_merchant_scopes scope
          JOIN identity.tenant_role_assignments assignment
            ON assignment.id = scope.role_assignment_id
          JOIN identity.tenant_memberships membership
            ON membership.id = assignment.membership_id
         WHERE scope.merchant_id = NEW.id
           AND membership.tenant_id <> NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'Merchant cannot move across Tenant boundaries while scoped';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER merchant_tenant_change_with_scopes
    AFTER UPDATE OF tenant_id ON merchant.merchants
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.reject_merchant_tenant_change_with_scopes();
