INSERT INTO tenancy.tenants (
    id,
    name,
    default_currency,
    default_locale,
    status,
    version,
    created_at,
    updated_at
) VALUES (
    '10000000-0000-0000-0000-000000000001',
    'LedgerOps Release 0.1 Demo Tenant',
    'SAR',
    'en-SA',
    'ACTIVE',
    0,
    '2026-07-21T00:00:00Z',
    '2026-07-21T00:00:00Z'
) ON CONFLICT DO NOTHING;

INSERT INTO merchant.merchants (
    id,
    tenant_id,
    name,
    status,
    version,
    created_at,
    updated_at
) VALUES (
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    'LedgerOps Demo Merchant',
    'ACTIVE',
    0,
    '2026-07-21T00:00:00Z',
    '2026-07-21T00:00:00Z'
) ON CONFLICT DO NOTHING;

INSERT INTO customer.customers (
    id,
    tenant_id,
    merchant_id,
    customer_reference,
    status,
    version,
    created_at,
    updated_at
) VALUES (
    '30000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    'release-01-synthetic-customer',
    'ACTIVE',
    0,
    '2026-07-21T00:00:00Z',
    '2026-07-21T00:00:00Z'
) ON CONFLICT DO NOTHING;

INSERT INTO ledger.accounts (
    id,
    tenant_id,
    account_code,
    currency,
    status,
    created_at
) VALUES
    (
        '51000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'CUSTOMER_RECEIVABLE',
        'SAR',
        'ACTIVE',
        '2026-07-21T00:00:00Z'
    ),
    (
        '52000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'MERCHANT_PAYABLE',
        'SAR',
        'ACTIVE',
        '2026-07-21T00:00:00Z'
    ),
    (
        '53000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'PROVIDER_CLEARING',
        'SAR',
        'ACTIVE',
        '2026-07-21T00:00:00Z'
    ),
    (
        '54000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'PLATFORM_FEE_REVENUE',
        'SAR',
        'ACTIVE',
        '2026-07-21T00:00:00Z'
    ),
    (
        '55000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'REVERSAL_PAYABLE',
        'SAR',
        'ACTIVE',
        '2026-07-21T00:00:00Z'
    ),
    (
        '56000000-0000-0000-0000-000000000001',
        '10000000-0000-0000-0000-000000000001',
        'SETTLEMENT_RECEIVABLE',
        'SAR',
        'ACTIVE',
        '2026-07-21T00:00:00Z'
    )
ON CONFLICT DO NOTHING;

INSERT INTO risk.risk_profiles (
    id,
    tenant_id,
    version,
    review_threshold,
    reject_threshold,
    active,
    created_at
) VALUES (
    '60000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    1,
    30,
    70,
    true,
    '2026-07-21T00:00:00Z'
) ON CONFLICT DO NOTHING;

INSERT INTO risk.payment_amount_threshold_rules (
    id,
    tenant_id,
    profile_id,
    currency,
    amount_threshold,
    score_contribution,
    enabled
) VALUES (
    '61000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '60000000-0000-0000-0000-000000000001',
    'SAR',
    100.00,
    35,
    true
) ON CONFLICT DO NOTHING;
