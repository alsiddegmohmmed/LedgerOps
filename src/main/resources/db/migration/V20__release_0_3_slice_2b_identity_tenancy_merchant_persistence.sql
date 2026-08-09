ALTER TABLE messaging.outbox
    DROP CONSTRAINT ck_outbox_producer;

ALTER TABLE messaging.outbox
    ADD CONSTRAINT ck_outbox_producer
        CHECK (producer_name IN ('payment', 'provider', 'tenancy', 'merchant', 'identity'));

CREATE FUNCTION tenancy.valid_currency_codes(currency_codes TEXT[])
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT cardinality(currency_codes) > 0
       AND NOT EXISTS (
            SELECT 1
              FROM unnest(currency_codes) AS currency_code
             WHERE currency_code IS NULL
                OR currency_code !~ '^[A-Z]{3}$'
       );
$$;

CREATE TABLE tenancy.tenant_configurations (
    tenant_id UUID NOT NULL,
    version BIGINT NOT NULL,
    allowed_currencies TEXT[] NOT NULL,
    default_locale VARCHAR(35) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    display_settings JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    actor_identity VARCHAR(512) NOT NULL,

    CONSTRAINT pk_tenant_configurations PRIMARY KEY (tenant_id, version),
    CONSTRAINT fk_tenant_configurations_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_configurations_version CHECK (version > 0),
    CONSTRAINT ck_tenant_configurations_allowed_currencies
        CHECK (tenancy.valid_currency_codes(allowed_currencies)),
    CONSTRAINT ck_tenant_configurations_default_locale
        CHECK (length(trim(default_locale)) > 0 AND default_locale = trim(default_locale)),
    CONSTRAINT ck_tenant_configurations_timezone
        CHECK (length(trim(timezone)) > 0 AND timezone = trim(timezone)),
    CONSTRAINT ck_tenant_configurations_display_settings
        CHECK (jsonb_typeof(display_settings) = 'object'),
    CONSTRAINT ck_tenant_configurations_actor
        CHECK (length(trim(actor_identity)) > 0 AND actor_identity = trim(actor_identity))
);

CREATE INDEX ix_tenant_configurations_current
    ON tenancy.tenant_configurations (tenant_id, version DESC);

CREATE TABLE tenancy.operational_contacts (
    tenant_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    version BIGINT NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    email VARCHAR(320) NOT NULL,
    purpose VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    actor_identity VARCHAR(512) NOT NULL,

    CONSTRAINT pk_operational_contacts PRIMARY KEY (tenant_id, contact_id, version),
    CONSTRAINT fk_operational_contacts_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operational_contacts_version CHECK (version > 0),
    CONSTRAINT ck_operational_contacts_display_name
        CHECK (length(trim(display_name)) > 0 AND display_name = trim(display_name)),
    CONSTRAINT ck_operational_contacts_email
        CHECK (
            length(trim(email)) > 0
            AND email = lower(trim(email))
            AND email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
        ),
    CONSTRAINT ck_operational_contacts_purpose
        CHECK (length(trim(purpose)) > 0 AND purpose = trim(purpose)),
    CONSTRAINT ck_operational_contacts_actor
        CHECK (length(trim(actor_identity)) > 0 AND actor_identity = trim(actor_identity))
);

CREATE INDEX ix_operational_contacts_current
    ON tenancy.operational_contacts (tenant_id, contact_id, version DESC);

CREATE FUNCTION tenancy.enforce_operational_contact_tenant_ownership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.contact_id::text, 0));

    IF EXISTS (
        SELECT 1
          FROM tenancy.operational_contacts
         WHERE contact_id = NEW.contact_id
           AND tenant_id <> NEW.tenant_id
    ) THEN
        RAISE EXCEPTION 'Operational contact Tenant ownership is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER operational_contact_tenant_ownership_immutable
BEFORE INSERT ON tenancy.operational_contacts
FOR EACH ROW EXECUTE FUNCTION tenancy.enforce_operational_contact_tenant_ownership();

CREATE FUNCTION tenancy.reject_append_only_version_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER tenant_configurations_append_only
BEFORE UPDATE OR DELETE ON tenancy.tenant_configurations
FOR EACH ROW EXECUTE FUNCTION tenancy.reject_append_only_version_mutation();

CREATE TRIGGER operational_contacts_append_only
BEFORE UPDATE OR DELETE ON tenancy.operational_contacts
FOR EACH ROW EXECUTE FUNCTION tenancy.reject_append_only_version_mutation();

CREATE INDEX ix_tenants_lifecycle_lock
    ON tenancy.tenants (id, status, version);

ALTER TABLE merchant.merchants
    ADD CONSTRAINT uk_merchants_tenant_id_id UNIQUE (tenant_id, id),
    ADD CONSTRAINT fk_merchants_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenants (id) ON DELETE RESTRICT;

CREATE INDEX ix_merchants_tenant_lifecycle_lock
    ON merchant.merchants (tenant_id, id, status, version);

CREATE FUNCTION merchant.reject_merchant_tenant_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        RAISE EXCEPTION 'Merchant Tenant ownership is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER merchant_tenant_ownership_immutable
BEFORE UPDATE OF tenant_id ON merchant.merchants
FOR EACH ROW EXECUTE FUNCTION merchant.reject_merchant_tenant_change();

ALTER TABLE identity.tenant_memberships
    DROP CONSTRAINT ck_tenant_memberships_status,
    ALTER COLUMN application_user_id DROP NOT NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT uk_tenant_memberships_tenant_id_id UNIQUE (tenant_id, id),
    ADD CONSTRAINT fk_tenant_memberships_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenancy.tenants (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_tenant_memberships_status
        CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED')),
    ADD CONSTRAINT ck_tenant_memberships_user_linkage
        CHECK (
            (status = 'INVITED' AND application_user_id IS NULL)
            OR (status IN ('ACTIVE', 'SUSPENDED') AND application_user_id IS NOT NULL)
            OR status = 'REVOKED'
        ),
    ADD CONSTRAINT ck_tenant_memberships_version CHECK (version >= 0);

CREATE INDEX ix_tenant_memberships_tenant_lock
    ON identity.tenant_memberships (tenant_id, status, id);

CREATE INDEX ix_tenant_role_assignments_membership_lock
    ON identity.tenant_role_assignments (membership_id, id);

CREATE INDEX ix_role_assignment_merchant_scopes_merchant
    ON identity.role_assignment_merchant_scopes (merchant_id);

CREATE TABLE identity.invitations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    intended_email VARCHAR(320) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT uk_invitations_membership UNIQUE (membership_id),
    CONSTRAINT uk_invitations_tenant_id_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_invitations_membership_tenant
        FOREIGN KEY (tenant_id, membership_id)
        REFERENCES identity.tenant_memberships (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_invitations_email
        CHECK (
            length(trim(intended_email)) > 0
            AND intended_email = lower(trim(intended_email))
            AND intended_email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
        ),
    CONSTRAINT ck_invitations_token_hash
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_invitations_status
        CHECK (status IN ('PENDING', 'REVOKED', 'CONSUMED')),
    CONSTRAINT ck_invitations_version CHECK (version >= 0),
    CONSTRAINT ck_invitations_seven_day_expiry
        CHECK (expires_at = created_at + INTERVAL '7 days'),
    CONSTRAINT ck_invitations_updated_at
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_invitations_terminal_chronology
        CHECK (
            (revoked_at IS NULL
                OR (revoked_at >= created_at AND revoked_at <= updated_at))
            AND (consumed_at IS NULL
                OR (consumed_at >= created_at
                    AND consumed_at <= updated_at
                    AND consumed_at < expires_at))
        ),
    CONSTRAINT ck_invitations_terminal_evidence
        CHECK (
            (status = 'PENDING' AND revoked_at IS NULL AND consumed_at IS NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND consumed_at IS NULL)
            OR (status = 'CONSUMED' AND revoked_at IS NULL AND consumed_at IS NOT NULL)
        )
);

CREATE INDEX ix_invitations_tenant_lock
    ON identity.invitations (tenant_id, status, id);

CREATE INDEX ix_invitations_pending_expiry
    ON identity.invitations (expires_at, tenant_id, id)
    WHERE status = 'PENDING';

CREATE TABLE identity.invitation_grants (
    invitation_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    scope_mode VARCHAR(16) NOT NULL,

    CONSTRAINT pk_invitation_grants PRIMARY KEY (invitation_id, assignment_id),
    CONSTRAINT uk_invitation_grants_invitation_role
        UNIQUE (invitation_id, role),
    CONSTRAINT uk_invitation_grants_tenant_identity
        UNIQUE (tenant_id, invitation_id, assignment_id),
    CONSTRAINT fk_invitation_grants_invitation_tenant
        FOREIGN KEY (tenant_id, invitation_id)
        REFERENCES identity.invitations (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_invitation_grants_role
        CHECK (role IN (
            'TENANT_ADMIN', 'MERCHANT_ADMIN', 'OPERATIONS_AGENT',
            'RISK_ANALYST', 'RECONCILIATION_ANALYST', 'AUDITOR',
            'VIEWER', 'INTEGRATION_DEVELOPER'
        )),
    CONSTRAINT ck_invitation_grants_scope_mode
        CHECK (scope_mode IN ('TENANT_WIDE', 'MERCHANT_SET')),
    CONSTRAINT ck_invitation_grants_role_scope
        CHECK (
            (role IN ('TENANT_ADMIN', 'RECONCILIATION_ANALYST')
                AND scope_mode = 'TENANT_WIDE')
            OR (role IN ('MERCHANT_ADMIN', 'INTEGRATION_DEVELOPER')
                AND scope_mode = 'MERCHANT_SET')
            OR role IN ('OPERATIONS_AGENT', 'RISK_ANALYST', 'AUDITOR', 'VIEWER')
        )
);

CREATE INDEX ix_invitation_grants_lock
    ON identity.invitation_grants (tenant_id, invitation_id, assignment_id);

CREATE TABLE identity.invitation_grant_merchant_scopes (
    invitation_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,

    CONSTRAINT pk_invitation_grant_merchant_scopes
        PRIMARY KEY (invitation_id, assignment_id, merchant_id),
    CONSTRAINT fk_invitation_grant_scopes_grant
        FOREIGN KEY (tenant_id, invitation_id, assignment_id)
        REFERENCES identity.invitation_grants (tenant_id, invitation_id, assignment_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_invitation_grant_scopes_merchant_tenant
        FOREIGN KEY (tenant_id, merchant_id)
        REFERENCES merchant.merchants (tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX ix_invitation_grant_scopes_lock
    ON identity.invitation_grant_merchant_scopes
        (tenant_id, invitation_id, assignment_id, merchant_id);

CREATE INDEX ix_invitation_grant_scopes_merchant_tenant
    ON identity.invitation_grant_merchant_scopes (tenant_id, merchant_id);

CREATE FUNCTION identity.enforce_invitation_grant_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_invitation_id UUID;
    target_assignment_id UUID;
    assignment_scope VARCHAR(16);
    merchant_scope_count INTEGER;
BEGIN
    target_invitation_id := COALESCE(NEW.invitation_id, OLD.invitation_id);
    target_assignment_id := COALESCE(NEW.assignment_id, OLD.assignment_id);

    SELECT scope_mode
      INTO assignment_scope
      FROM identity.invitation_grants
     WHERE invitation_id = target_invitation_id
       AND assignment_id = target_assignment_id;

    IF assignment_scope IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT count(*)
      INTO merchant_scope_count
      FROM identity.invitation_grant_merchant_scopes
     WHERE invitation_id = target_invitation_id
       AND assignment_id = target_assignment_id;

    IF assignment_scope = 'MERCHANT_SET' AND merchant_scope_count = 0 THEN
        RAISE EXCEPTION 'Merchant-scoped invitation grant requires at least one Merchant';
    END IF;
    IF assignment_scope = 'TENANT_WIDE' AND merchant_scope_count <> 0 THEN
        RAISE EXCEPTION 'Tenant-wide invitation grant cannot have Merchant scopes';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER invitation_grant_scope_complete
    AFTER INSERT OR UPDATE ON identity.invitation_grants
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_invitation_grant_scope();

CREATE CONSTRAINT TRIGGER invitation_grant_merchant_scope_valid
    AFTER INSERT OR UPDATE OR DELETE ON identity.invitation_grant_merchant_scopes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_invitation_grant_scope();

CREATE FUNCTION identity.enforce_invitation_has_grant()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_invitation_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'invitations' THEN
        target_invitation_id := COALESCE(NEW.id, OLD.id);
    ELSE
        target_invitation_id := COALESCE(NEW.invitation_id, OLD.invitation_id);
    END IF;

    IF EXISTS (SELECT 1 FROM identity.invitations WHERE id = target_invitation_id)
       AND NOT EXISTS (
            SELECT 1
              FROM identity.invitation_grants
             WHERE invitation_id = target_invitation_id
       ) THEN
        RAISE EXCEPTION 'Invitation requires at least one proposed role assignment';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER invitation_has_grant
    AFTER INSERT OR UPDATE ON identity.invitations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_invitation_has_grant();

CREATE CONSTRAINT TRIGGER invitation_grant_keeps_invitation_nonempty
    AFTER INSERT OR UPDATE OR DELETE ON identity.invitation_grants
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_invitation_has_grant();

CREATE FUNCTION identity.enforce_invitation_membership_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_membership_id UUID;
    invitation_status VARCHAR(16);
    membership_status VARCHAR(16);
BEGIN
    IF TG_TABLE_NAME = 'invitations' THEN
        target_membership_id := COALESCE(NEW.membership_id, OLD.membership_id);
    ELSE
        target_membership_id := COALESCE(NEW.id, OLD.id);
    END IF;

    SELECT status
      INTO membership_status
      FROM identity.tenant_memberships
     WHERE id = target_membership_id;

    IF membership_status IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT status
      INTO invitation_status
      FROM identity.invitations
     WHERE membership_id = target_membership_id;

    IF invitation_status IS NULL THEN
        IF membership_status = 'INVITED' THEN
            RAISE EXCEPTION 'Invited membership requires an invitation with durable grant intent';
        END IF;
        RETURN NULL;
    END IF;

    IF (invitation_status = 'PENDING' AND membership_status <> 'INVITED')
       OR (invitation_status = 'CONSUMED'
           AND membership_status NOT IN ('ACTIVE', 'SUSPENDED', 'REVOKED'))
       OR (invitation_status = 'REVOKED' AND membership_status <> 'REVOKED') THEN
        RAISE EXCEPTION 'Invitation and membership lifecycle states must change atomically';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER invitation_membership_state
    AFTER INSERT OR UPDATE ON identity.invitations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_invitation_membership_state();

CREATE CONSTRAINT TRIGGER membership_invitation_state
    AFTER INSERT OR UPDATE ON identity.tenant_memberships
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION identity.enforce_invitation_membership_state();

CREATE FUNCTION identity.reject_invalid_invitation_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING' OR NEW.version <> 0 THEN
            RAISE EXCEPTION 'Invitation must be created PENDING at version zero';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('REVOKED', 'CONSUMED') THEN
        RAISE EXCEPTION 'Terminal invitation evidence is immutable';
    END IF;
    IF NOT (OLD.status = 'PENDING' AND NEW.status IN ('REVOKED', 'CONSUMED')) THEN
        RAISE EXCEPTION 'Invalid invitation lifecycle transition from % to %', OLD.status, NEW.status;
    END IF;
    IF NEW.status = 'CONSUMED'
       AND transaction_timestamp() >= OLD.expires_at THEN
        RAISE EXCEPTION 'Expired invitation cannot be consumed';
    END IF;
    IF NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.membership_id IS DISTINCT FROM OLD.membership_id
       OR NEW.intended_email IS DISTINCT FROM OLD.intended_email
       OR NEW.token_hash IS DISTINCT FROM OLD.token_hash
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'Invitation identity and grant intent are immutable';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Invitation version must increase by exactly one';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invitation_transition_valid
BEFORE INSERT OR UPDATE ON identity.invitations
FOR EACH ROW EXECUTE FUNCTION identity.reject_invalid_invitation_transition();

CREATE FUNCTION identity.reject_invalid_membership_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'REVOKED' THEN
        RAISE EXCEPTION 'Revoked membership evidence is immutable';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Membership Tenant ownership is immutable';
    END IF;
    IF OLD.status <> NEW.status
       AND NOT (
            (OLD.status = 'INVITED' AND NEW.status IN ('ACTIVE', 'REVOKED'))
            OR (OLD.status = 'ACTIVE' AND NEW.status IN ('SUSPENDED', 'REVOKED'))
            OR (OLD.status = 'SUSPENDED' AND NEW.status IN ('ACTIVE', 'REVOKED'))
       ) THEN
        RAISE EXCEPTION 'Invalid membership lifecycle transition from % to %', OLD.status, NEW.status;
    END IF;
    IF NEW.application_user_id IS DISTINCT FROM OLD.application_user_id
       AND NOT (
            OLD.status = 'INVITED'
            AND NEW.status = 'ACTIVE'
            AND OLD.application_user_id IS NULL
            AND NEW.application_user_id IS NOT NULL
       ) THEN
        RAISE EXCEPTION 'Linked membership identity is immutable';
    END IF;
    IF (OLD.status IS DISTINCT FROM NEW.status
        OR OLD.application_user_id IS DISTINCT FROM NEW.application_user_id)
       AND NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Membership version must increase by exactly one';
    END IF;
    IF OLD.status IS NOT DISTINCT FROM NEW.status
       AND OLD.application_user_id IS NOT DISTINCT FROM NEW.application_user_id
       AND (NEW.version IS DISTINCT FROM OLD.version
            OR NEW.updated_at IS DISTINCT FROM OLD.updated_at) THEN
        RAISE EXCEPTION 'Membership version changes require a lifecycle transition';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER membership_transition_valid
BEFORE UPDATE ON identity.tenant_memberships
FOR EACH ROW EXECUTE FUNCTION identity.reject_invalid_membership_transition();

CREATE FUNCTION identity.reject_membership_deletion()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Membership history cannot be physically deleted';
END;
$$;

CREATE TRIGGER tenant_membership_history_immutable
BEFORE DELETE ON identity.tenant_memberships
FOR EACH ROW EXECUTE FUNCTION identity.reject_membership_deletion();

CREATE FUNCTION identity.enforce_consumed_invitation_grant_copy()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_invitation_id UUID;
    target_membership_id UUID;
    invitation_status VARCHAR(16);
BEGIN
    IF TG_TABLE_NAME = 'invitations' THEN
        target_invitation_id := COALESCE(NEW.id, OLD.id);
    ELSE
        SELECT id
          INTO target_invitation_id
          FROM identity.invitations
         WHERE membership_id = COALESCE(NEW.id, OLD.id);
    END IF;

    IF target_invitation_id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT status, membership_id
      INTO invitation_status, target_membership_id
      FROM identity.invitations
     WHERE id = target_invitation_id;

    IF invitation_status IS DISTINCT FROM 'CONSUMED' THEN
        RETURN NULL;
    END IF;

    IF EXISTS (
        SELECT grant_intent.assignment_id
          FROM identity.invitation_grants grant_intent
         WHERE grant_intent.invitation_id = target_invitation_id
        EXCEPT
        SELECT assignment.id
          FROM identity.tenant_role_assignments assignment
          JOIN identity.invitation_grants grant_intent
            ON grant_intent.invitation_id = target_invitation_id
           AND grant_intent.assignment_id = assignment.id
           AND grant_intent.role = assignment.role
           AND grant_intent.scope_mode = assignment.scope_mode
         WHERE assignment.membership_id = target_membership_id
    ) OR EXISTS (
        SELECT assignment.id
          FROM identity.tenant_role_assignments assignment
         WHERE assignment.membership_id = target_membership_id
        EXCEPT
        SELECT grant_intent.assignment_id
          FROM identity.invitation_grants grant_intent
         WHERE grant_intent.invitation_id = target_invitation_id
    ) THEN
        RAISE EXCEPTION 'Consumed invitation grants must be copied exactly to active role assignments';
    END IF;

    IF EXISTS (
        SELECT grant_scope.assignment_id, grant_scope.merchant_id
          FROM identity.invitation_grant_merchant_scopes grant_scope
         WHERE grant_scope.invitation_id = target_invitation_id
        EXCEPT
        SELECT active_scope.role_assignment_id, active_scope.merchant_id
          FROM identity.role_assignment_merchant_scopes active_scope
          JOIN identity.tenant_role_assignments assignment
            ON assignment.id = active_scope.role_assignment_id
         WHERE assignment.membership_id = target_membership_id
    ) OR EXISTS (
        SELECT active_scope.role_assignment_id, active_scope.merchant_id
          FROM identity.role_assignment_merchant_scopes active_scope
          JOIN identity.tenant_role_assignments assignment
            ON assignment.id = active_scope.role_assignment_id
         WHERE assignment.membership_id = target_membership_id
        EXCEPT
        SELECT grant_scope.assignment_id, grant_scope.merchant_id
          FROM identity.invitation_grant_merchant_scopes grant_scope
         WHERE grant_scope.invitation_id = target_invitation_id
    ) THEN
        RAISE EXCEPTION 'Consumed invitation Merchant scopes must be copied exactly to active role assignments';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER consumed_invitation_grants_copied
AFTER INSERT OR UPDATE ON identity.invitations
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION identity.enforce_consumed_invitation_grant_copy();

CREATE CONSTRAINT TRIGGER consumed_membership_grants_copied
AFTER INSERT OR UPDATE ON identity.tenant_memberships
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION identity.enforce_consumed_invitation_grant_copy();

CREATE FUNCTION identity.enforce_active_membership_role_assignment()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    membership_status VARCHAR(16);
BEGIN
    SELECT status
      INTO membership_status
      FROM identity.tenant_memberships
     WHERE id = NEW.membership_id;

    IF membership_status IS DISTINCT FROM 'ACTIVE' THEN
        RAISE EXCEPTION 'Active role assignments require an ACTIVE membership';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tenant_role_assignment_requires_active_membership
AFTER INSERT OR UPDATE ON identity.tenant_role_assignments
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION identity.enforce_active_membership_role_assignment();

CREATE FUNCTION identity.require_pending_invitation_for_grant_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    invitation_status VARCHAR(16);
    invitation_created_in_current_transaction BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'invitation_grants' THEN
        SELECT status,
               xmin::text = pg_current_xact_id()::text
          INTO invitation_status, invitation_created_in_current_transaction
          FROM identity.invitations
         WHERE id = NEW.invitation_id
         FOR UPDATE;
    ELSE
        SELECT invitation.status,
               invitation.xmin::text = pg_current_xact_id()::text
          INTO invitation_status, invitation_created_in_current_transaction
          FROM identity.invitations invitation
          JOIN identity.invitation_grants grant_intent
            ON grant_intent.invitation_id = invitation.id
           AND grant_intent.assignment_id = NEW.assignment_id
         WHERE invitation.id = NEW.invitation_id
         FOR UPDATE OF invitation;
    END IF;

    IF invitation_status IS DISTINCT FROM 'PENDING'
       OR invitation_created_in_current_transaction IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Invitation grant intent can be added only in the invitation creation transaction';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER invitation_grants_pending_insert_only
BEFORE INSERT ON identity.invitation_grants
FOR EACH ROW EXECUTE FUNCTION identity.require_pending_invitation_for_grant_insert();

CREATE TRIGGER invitation_grant_scopes_pending_insert_only
BEFORE INSERT ON identity.invitation_grant_merchant_scopes
FOR EACH ROW EXECUTE FUNCTION identity.require_pending_invitation_for_grant_insert();

CREATE FUNCTION identity.reject_invitation_grant_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Invitation grant intent is immutable';
END;
$$;

CREATE TRIGGER invitation_grants_immutable
BEFORE UPDATE OR DELETE ON identity.invitation_grants
FOR EACH ROW EXECUTE FUNCTION identity.reject_invitation_grant_mutation();

CREATE TRIGGER invitation_grant_scopes_immutable
BEFORE UPDATE OR DELETE ON identity.invitation_grant_merchant_scopes
FOR EACH ROW EXECUTE FUNCTION identity.reject_invitation_grant_mutation();

CREATE FUNCTION identity.reject_role_assignment_identity_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Role assignment identity is immutable; create a new assignment instead';
END;
$$;

CREATE TRIGGER tenant_role_assignment_identity_immutable
BEFORE UPDATE ON identity.tenant_role_assignments
FOR EACH ROW EXECUTE FUNCTION identity.reject_role_assignment_identity_mutation();

CREATE TRIGGER role_assignment_merchant_scope_identity_immutable
BEFORE UPDATE ON identity.role_assignment_merchant_scopes
FOR EACH ROW EXECUTE FUNCTION identity.reject_role_assignment_identity_mutation();

CREATE FUNCTION identity.reject_consumed_invitation_assignment_reuse()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    target_assignment_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'tenant_role_assignments' THEN
        target_assignment_id := NEW.id;
    ELSE
        target_assignment_id := NEW.role_assignment_id;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM identity.invitation_grants grant_intent
          JOIN identity.invitations invitation
            ON invitation.id = grant_intent.invitation_id
         WHERE grant_intent.assignment_id = target_assignment_id
           AND invitation.status = 'CONSUMED'
    ) THEN
        RAISE EXCEPTION 'Consumed invitation assignment identity cannot be reused or extended';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER consumed_invitation_assignment_id_not_reused
BEFORE INSERT ON identity.tenant_role_assignments
FOR EACH ROW EXECUTE FUNCTION identity.reject_consumed_invitation_assignment_reuse();

CREATE TRIGGER consumed_invitation_assignment_scope_not_extended
BEFORE INSERT ON identity.role_assignment_merchant_scopes
FOR EACH ROW EXECUTE FUNCTION identity.reject_consumed_invitation_assignment_reuse();
