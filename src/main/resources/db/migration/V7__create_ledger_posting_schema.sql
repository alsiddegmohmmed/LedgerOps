ALTER TABLE ledger.accounts
    ADD CONSTRAINT uk_ledger_accounts_tenant_id_currency
        UNIQUE (tenant_id, id, currency);

CREATE TABLE ledger.transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id UUID NOT NULL,
    compensates_transaction_id UUID,
    posted_at TIMESTAMPTZ NOT NULL,
    currency CHAR(3) NOT NULL,
    entry_count INTEGER NOT NULL,
    debit_total NUMERIC(38, 18) NOT NULL,
    credit_total NUMERIC(38, 18) NOT NULL,

    CONSTRAINT uk_ledger_transactions_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT uk_ledger_transactions_tenant_id_currency
        UNIQUE (tenant_id, id, currency),

    CONSTRAINT uk_ledger_transactions_source
        UNIQUE (tenant_id, source_type, source_id),

    CONSTRAINT fk_ledger_transactions_compensates
        FOREIGN KEY (tenant_id, compensates_transaction_id)
        REFERENCES ledger.transactions (tenant_id, id),

    CONSTRAINT ck_ledger_transactions_source_type
        CHECK (source_type IN (
            'PAYMENT',
            'REVERSAL',
            'SETTLEMENT_ADJUSTMENT',
            'AUTHORISED_CORRECTION'
        )),

    CONSTRAINT ck_ledger_transactions_currency_format
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_ledger_transactions_entry_count
        CHECK (entry_count >= 2),

    CONSTRAINT ck_ledger_transactions_balanced_totals
        CHECK (
            debit_total > 0
            AND credit_total > 0
            AND debit_total = credit_total
        ),

    CONSTRAINT ck_ledger_transactions_not_self_compensating
        CHECK (
            compensates_transaction_id IS NULL
            OR compensates_transaction_id <> id
        )
);

CREATE TABLE ledger.entries (
    tenant_id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    entry_index INTEGER NOT NULL,
    account_id UUID NOT NULL,
    direction VARCHAR(8) NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    currency CHAR(3) NOT NULL,

    CONSTRAINT pk_ledger_entries
        PRIMARY KEY (tenant_id, transaction_id, entry_index),

    CONSTRAINT fk_ledger_entries_transaction
        FOREIGN KEY (tenant_id, transaction_id, currency)
        REFERENCES ledger.transactions (tenant_id, id, currency),

    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (tenant_id, account_id, currency)
        REFERENCES ledger.accounts (tenant_id, id, currency),

    CONSTRAINT ck_ledger_entries_index
        CHECK (entry_index >= 0),

    CONSTRAINT ck_ledger_entries_direction
        CHECK (direction IN ('DEBIT', 'CREDIT')),

    CONSTRAINT ck_ledger_entries_amount_positive
        CHECK (amount > 0),

    CONSTRAINT ck_ledger_entries_currency_format
        CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_ledger_entries_account_history
    ON ledger.entries (tenant_id, account_id, transaction_id, entry_index);

CREATE FUNCTION ledger.verify_posting_entries()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    actual_entry_count INTEGER;
    actual_debit_count INTEGER;
    actual_credit_count INTEGER;
    actual_debit_total NUMERIC(38, 18);
    actual_credit_total NUMERIC(38, 18);
BEGIN
    SELECT count(*),
           count(*) FILTER (WHERE direction = 'DEBIT'),
           count(*) FILTER (WHERE direction = 'CREDIT'),
           coalesce(sum(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           coalesce(sum(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO actual_entry_count,
           actual_debit_count,
           actual_credit_count,
           actual_debit_total,
           actual_credit_total
      FROM ledger.entries
     WHERE tenant_id = NEW.tenant_id
       AND transaction_id = NEW.id;

    IF actual_entry_count <> NEW.entry_count
        OR actual_debit_count = 0
        OR actual_credit_count = 0
        OR actual_debit_total <> NEW.debit_total
        OR actual_credit_total <> NEW.credit_total
        OR actual_debit_total <> actual_credit_total THEN
        RAISE EXCEPTION 'Ledger posting entries do not match balanced transaction totals';
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_transactions_verify_entries
    AFTER INSERT ON ledger.transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger.verify_posting_entries();

CREATE FUNCTION ledger.reject_posting_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Posted Ledger transactions and entries are append-only';
END;
$$;

CREATE TRIGGER trg_ledger_transactions_append_only
    BEFORE UPDATE OR DELETE ON ledger.transactions
    FOR EACH ROW
    EXECUTE FUNCTION ledger.reject_posting_mutation();

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger.entries
    FOR EACH ROW
    EXECUTE FUNCTION ledger.reject_posting_mutation();
