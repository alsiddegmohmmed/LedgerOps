#!/usr/bin/env bash
set -euo pipefail

if [[ "${LEDGEROPS_DEMO_CONFIRM:-}" != "YES" ]]; then
  echo "Refusing to seed a database without LEDGEROPS_DEMO_CONFIRM=YES." >&2
  exit 2
fi

db_user="${LEDGEROPS_DEMO_DB_USER:-ledgerops}"
db_name="${LEDGEROPS_DEMO_DB_NAME:-ledgerops}"
postgres_container="${LEDGEROPS_DEMO_POSTGRES_CONTAINER:-ledgerops-core-postgres-1}"

run_psql() {
  if [[ "${LEDGEROPS_DEMO_PSQL_MODE:-docker}" == "docker" ]]; then
    docker exec -i "$postgres_container" psql \
      -v ON_ERROR_STOP=1 \
      -U "$db_user" \
      -d "$db_name"
  else
    psql -v ON_ERROR_STOP=1 -U "$db_user" -d "$db_name"
  fi
}

run_psql <<'SQL'
BEGIN;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM tenancy.tenants
         WHERE id = '00000000-0000-4000-8000-000000000001'
           AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'The expected active demo Tenant does not exist';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM merchant.merchants
         WHERE id = '00000000-0000-4000-8000-000000000011'
           AND tenant_id = '00000000-0000-4000-8000-000000000001'
           AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'The expected active demo Merchant does not exist';
    END IF;
END;
$$;

INSERT INTO ledger.accounts
    (id, tenant_id, account_code, currency, status, created_at)
VALUES
    ('70000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000001', 'PROVIDER_CLEARING', 'USD', 'ACTIVE', now()),
    ('70000000-0000-4000-8000-000000000002',
     '00000000-0000-4000-8000-000000000001', 'MERCHANT_PAYABLE', 'USD', 'ACTIVE', now())
ON CONFLICT (tenant_id, account_code, currency) DO NOTHING;

INSERT INTO payment.payments
    (id, tenant_id, merchant_id, customer_id, amount, currency,
     payment_method_category, idempotency_key, request_fingerprint,
     status, version, created_at, updated_at)
VALUES
    ('00000000-0000-4000-8000-000000000021',
     '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000011',
     '60000000-0000-4000-8000-000000000001',
     125.00, 'USD', 'CARD', 'ledgerops-demo-payment-021', repeat('c', 64),
     'COMPLETED', 0, now() - interval '2 hours', now() - interval '2 hours'),
    ('00000000-0000-4000-8000-000000000022',
     '00000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000011',
     '60000000-0000-4000-8000-000000000002',
     75.00, 'USD', 'CARD', 'ledgerops-demo-payment-022', repeat('d', 64),
     'FAILED', 0, now() - interval '1 hour', now() - interval '1 hour')
ON CONFLICT (tenant_id, id) DO NOTHING;

INSERT INTO ledger.transactions
    (id, tenant_id, source_type, source_id, compensates_transaction_id,
     posted_at, currency, entry_count, debit_total, credit_total)
VALUES
    ('80000000-0000-4000-8000-000000000001',
     '00000000-0000-4000-8000-000000000001', 'PAYMENT',
     '00000000-0000-4000-8000-000000000021', NULL,
     now() - interval '2 hours', 'USD', 2, 125.00, 125.00)
ON CONFLICT (tenant_id, source_type, source_id) DO NOTHING;

INSERT INTO ledger.entries
    (tenant_id, transaction_id, entry_index, account_id, direction, amount, currency)
VALUES
    ('00000000-0000-4000-8000-000000000001',
     '80000000-0000-4000-8000-000000000001', 0,
     '70000000-0000-4000-8000-000000000001', 'DEBIT', 125.00, 'USD'),
    ('00000000-0000-4000-8000-000000000001',
     '80000000-0000-4000-8000-000000000001', 1,
     '70000000-0000-4000-8000-000000000002', 'CREDIT', 125.00, 'USD')
ON CONFLICT (tenant_id, transaction_id, entry_index) DO NOTHING;

DO $$
BEGIN
    IF (SELECT count(*) FROM payment.payments
         WHERE tenant_id = '00000000-0000-4000-8000-000000000001'
           AND id IN (
               '00000000-0000-4000-8000-000000000021',
               '00000000-0000-4000-8000-000000000022'
           )) <> 2 THEN
        RAISE EXCEPTION 'Demo Payment seed verification failed';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM ledger.transactions
         WHERE tenant_id = '00000000-0000-4000-8000-000000000001'
           AND source_type = 'PAYMENT'
           AND source_id = '00000000-0000-4000-8000-000000000021'
           AND debit_total = 125.00
           AND credit_total = 125.00
    ) THEN
        RAISE EXCEPTION 'Demo Ledger seed verification failed';
    END IF;
END;
$$;

COMMIT;
SQL

echo "Seeded authoritative demo Payment and Ledger source records."
echo "Reporting projections were not modified; run the separate local rebuild step next."
