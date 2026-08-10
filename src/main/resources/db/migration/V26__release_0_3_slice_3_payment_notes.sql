CREATE TABLE payment.notes (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    author_issuer VARCHAR(512) NOT NULL,
    author_subject VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_payment_note_payment
        FOREIGN KEY (payment_id) REFERENCES payment.payments (id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_note_author_issuer
        CHECK (length(trim(author_issuer)) > 0),
    CONSTRAINT ck_payment_note_author_subject
        CHECK (length(trim(author_subject)) > 0),
    CONSTRAINT ck_payment_note_content
        CHECK (length(trim(content)) > 0 AND length(content) <= 4000)
);

CREATE INDEX ix_payment_notes_tenant_payment_created
    ON payment.notes (tenant_id, payment_id, created_at, id);

CREATE FUNCTION payment.reject_payment_note_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Payment notes are append-only';
END;
$$;

CREATE TRIGGER payment_notes_append_only
BEFORE UPDATE OR DELETE ON payment.notes
FOR EACH ROW EXECUTE FUNCTION payment.reject_payment_note_mutation();
