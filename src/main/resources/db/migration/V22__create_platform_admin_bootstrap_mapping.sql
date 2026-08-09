CREATE TABLE identity.platform_admin_assignments (
    issuer VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_platform_admin_assignments PRIMARY KEY (issuer, subject),
    CONSTRAINT ck_platform_admin_assignments_issuer
        CHECK (length(trim(issuer)) > 0),
    CONSTRAINT ck_platform_admin_assignments_subject
        CHECK (length(trim(subject)) > 0),
    CONSTRAINT ck_platform_admin_assignments_role
        CHECK (role = 'PLATFORM_ADMIN')
);
