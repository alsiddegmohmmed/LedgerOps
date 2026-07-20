CREATE SCHEMA ledger;

CREATE TABLE ledger.accounts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    account_code VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_ledger_accounts_tenant_code_currency
        UNIQUE (tenant_id, account_code, currency),

    CONSTRAINT uk_ledger_accounts_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT ck_ledger_accounts_code
        CHECK (account_code IN (
            'CUSTOMER_RECEIVABLE',
            'MERCHANT_PAYABLE',
            'PROVIDER_CLEARING',
            'PLATFORM_FEE_REVENUE',
            'REVERSAL_PAYABLE',
            'SETTLEMENT_RECEIVABLE'
        )),

    CONSTRAINT ck_ledger_accounts_currency_format
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_ledger_accounts_status
        CHECK (status = 'ACTIVE')
);

CREATE FUNCTION ledger.reject_account_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Ledger accounts are immutable and cannot be deleted';
END;
$$;

CREATE TRIGGER trg_ledger_accounts_immutable
    BEFORE UPDATE OR DELETE ON ledger.accounts
    FOR EACH ROW
    EXECUTE FUNCTION ledger.reject_account_mutation();
