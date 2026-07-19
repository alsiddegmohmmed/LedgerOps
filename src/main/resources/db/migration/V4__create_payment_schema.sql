CREATE SCHEMA payment;

CREATE TABLE payment.payments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    merchant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    currency CHAR(3) NOT NULL,
    payment_method_category TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uk_payments_tenant_idempotency
        UNIQUE (tenant_id, idempotency_key),

    CONSTRAINT ck_payments_amount_positive
        CHECK (amount > 0),

    CONSTRAINT ck_payments_currency_format
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_payments_method_category_not_blank
        CHECK (length(trim(payment_method_category)) > 0),

    CONSTRAINT ck_payments_idempotency_key_not_blank
        CHECK (length(trim(idempotency_key)) > 0),

    CONSTRAINT ck_payments_request_fingerprint
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),

    CONSTRAINT ck_payments_status
        CHECK (
            status IN (
                'CREATED',
                'VALIDATING',
                'RISK_REVIEW',
                'APPROVED',
                'REJECTED',
                'PROCESSING',
                'COMPLETED',
                'FAILED',
                'REVERSED'
            )
        )
);

CREATE INDEX ix_payments_tenant_merchant
    ON payment.payments (tenant_id, merchant_id);

CREATE INDEX ix_payments_tenant_customer
    ON payment.payments (tenant_id, customer_id);
