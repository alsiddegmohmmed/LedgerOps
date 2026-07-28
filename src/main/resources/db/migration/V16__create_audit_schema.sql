CREATE SCHEMA audit;

CREATE TABLE audit.audit_records (
    id UUID PRIMARY KEY,
    actor_issuer VARCHAR(255) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    principal_type VARCHAR(16) NOT NULL,
    tenant_id UUID,
    action_type VARCHAR(120) NOT NULL,
    target_type VARCHAR(120) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    correlation_id VARCHAR(120) NOT NULL,
    reason TEXT NOT NULL,
    details TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_audit_actor_issuer_not_blank
        CHECK (length(trim(actor_issuer)) > 0),
    CONSTRAINT ck_audit_actor_subject_not_blank
        CHECK (length(trim(actor_subject)) > 0),
    CONSTRAINT ck_audit_principal_type
        CHECK (principal_type IN ('HUMAN', 'SERVICE')),
    CONSTRAINT ck_audit_action_type_not_blank
        CHECK (length(trim(action_type)) > 0),
    CONSTRAINT ck_audit_target_type_not_blank
        CHECK (length(trim(target_type)) > 0),
    CONSTRAINT ck_audit_target_id_not_blank
        CHECK (length(trim(target_id)) > 0),
    CONSTRAINT ck_audit_correlation_id_not_blank
        CHECK (length(trim(correlation_id)) > 0),
    CONSTRAINT ck_audit_reason_not_blank
        CHECK (length(trim(reason)) > 0),
    CONSTRAINT ck_audit_details_not_blank
        CHECK (length(trim(details)) > 0)
);

CREATE INDEX ix_audit_records_tenant_occurred_at
    ON audit.audit_records (tenant_id, occurred_at);

CREATE FUNCTION audit.reject_audit_record_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Audit records are append-only';
END;
$$;

CREATE TRIGGER audit_records_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_records
    FOR EACH ROW EXECUTE FUNCTION audit.reject_audit_record_mutation();
