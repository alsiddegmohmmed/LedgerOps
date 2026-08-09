ALTER TABLE identity.tenant_memberships
    ADD COLUMN is_initial BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uk_tenant_memberships_initial_tenant
    ON identity.tenant_memberships (tenant_id)
    WHERE is_initial;

CREATE FUNCTION identity.reject_tenant_membership_initial_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.is_initial IS DISTINCT FROM OLD.is_initial THEN
        RAISE EXCEPTION 'Tenant membership initial identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tenant_membership_initial_immutable
BEFORE UPDATE OF is_initial ON identity.tenant_memberships
FOR EACH ROW EXECUTE FUNCTION identity.reject_tenant_membership_initial_mutation();
