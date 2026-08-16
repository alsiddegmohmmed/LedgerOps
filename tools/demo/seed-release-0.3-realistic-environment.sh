#!/usr/bin/env bash
set -euo pipefail

# Local-only, additive demo data. This script never drops or truncates tables.
if [[ "${LEDGEROPS_DEMO_CONFIRM:-}" != "YES" ]]; then
  echo "Refusing to seed a database without LEDGEROPS_DEMO_CONFIRM=YES." >&2
  exit 2
fi

postgres_container="${LEDGEROPS_DEMO_POSTGRES_CONTAINER:-ledgerops-core-postgres-1}"
db_user="${LEDGEROPS_DEMO_DB_USER:-ledgerops}"
db_name="${LEDGEROPS_DEMO_DB_NAME:-ledgerops}"
keycloak_url="${LEDGEROPS_DEMO_KEYCLOAK_URL:-http://localhost:8180}"
realm="${LEDGEROPS_DEMO_KEYCLOAK_REALM:-ledgerops}"

run_psql() {
  docker exec -i "$postgres_container" psql \
    -v ON_ERROR_STOP=1 \
    -U "$db_user" \
    -d "$db_name" "$@"
}

echo "Creating or verifying local Keycloak demo users..."
if ! command -v curl >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1; then
  echo "curl and python3 are required for the local Keycloak user seed." >&2
  exit 2
fi

admin_token="$(curl -fsS -X POST "$keycloak_url/realms/master/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'username=admin' \
  --data-urlencode 'password=admin' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=admin-cli' | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"

ensure_keycloak_user() {
  local id="$1" username="$2" password="$3" email="$4" first_name="$5" last_name="$6"
  local response existing_id status
  response="$(curl -fsS -G "$keycloak_url/admin/realms/$realm/users" \
    -H "Authorization: Bearer $admin_token" \
    --data-urlencode "username=$username" \
    --data-urlencode 'exact=true')"
  existing_id="$(printf '%s' "$response" | python3 -c 'import json,sys; rows=json.load(sys.stdin); print(rows[0]["id"] if rows else "")')"

  if [[ -z "$existing_id" ]]; then
    status="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$keycloak_url/admin/realms/$realm/users" \
      -H "Authorization: Bearer $admin_token" \
      -H 'Content-Type: application/json' \
      --data "$(python3 - "$id" "$username" "$password" "$email" "$first_name" "$last_name" <<'PY'
import json, sys
user_id, username, password, email, first_name, last_name = sys.argv[1:]
print(json.dumps({
    "id": user_id,
    "username": username,
    "enabled": True,
    "emailVerified": True,
    "email": email,
    "firstName": first_name,
    "lastName": last_name,
    "requiredActions": [],
    "credentials": [{"type": "password", "value": password, "temporary": False}],
}))
PY
)")"
    if [[ "$status" != "201" && "$status" != "409" ]]; then
      echo "Could not create Keycloak user $username (HTTP $status)." >&2
      exit 1
    fi
    response="$(curl -fsS -G "$keycloak_url/admin/realms/$realm/users" \
      -H "Authorization: Bearer $admin_token" \
      --data-urlencode "username=$username" \
      --data-urlencode 'exact=true')"
    existing_id="$(printf '%s' "$response" | python3 -c 'import json,sys; rows=json.load(sys.stdin); print(rows[0]["id"] if rows else "")')"
  fi

  if [[ "$existing_id" != "$id" ]]; then
    echo "Keycloak assigned subject $existing_id to $username (requested seed ID $id). Using the actual subject." >&2
  fi

  curl -fsS -X PUT "$keycloak_url/admin/realms/$realm/users/$existing_id" \
    -H "Authorization: Bearer $admin_token" \
    -H 'Content-Type: application/json' \
    --data "$(python3 - "$username" "$email" "$first_name" "$last_name" <<'PY'
import json, sys
username, email, first_name, last_name = sys.argv[1:]
print(json.dumps({
    "username": username,
    "enabled": True,
    "emailVerified": True,
    "email": email,
    "firstName": first_name,
    "lastName": last_name,
}))
PY
  )" >/dev/null

  curl -fsS -X PUT "$keycloak_url/admin/realms/$realm/users/$existing_id/reset-password" \
    -H "Authorization: Bearer $admin_token" \
    -H 'Content-Type: application/json' \
    --data "$(python3 - "$password" <<'PY'
import json, sys
print(json.dumps({"type": "password", "value": sys.argv[1], "temporary": False}))
PY
  )" >/dev/null

  local subject_variable="subject_${username//-/_}"
  printf -v "$subject_variable" '%s' "$existing_id"
}

ensure_keycloak_user "11111111-1111-4111-8111-111111111111" "slice1-human" "slice1-password" "slice1-human@example.com" "Slice" "Human"
ensure_keycloak_user "11111111-1111-4111-8111-111111111102" "tenant-admin" "tenant-admin-password" "tenant-admin@example.com" "Tenant" "Administrator"
ensure_keycloak_user "11111111-1111-4111-8111-111111111103" "merchant-admin" "merchant-admin-password" "merchant-admin@example.com" "Merchant" "Administrator"
ensure_keycloak_user "11111111-1111-4111-8111-111111111104" "operations-agent" "operations-agent-password" "operations-agent@example.com" "Operations" "Agent"
ensure_keycloak_user "11111111-1111-4111-8111-111111111105" "risk-analyst" "risk-analyst-password" "risk-analyst@example.com" "Risk" "Analyst"
ensure_keycloak_user "11111111-1111-4111-8111-111111111106" "reconciliation-analyst" "reconciliation-analyst-password" "reconciliation-analyst@example.com" "Reconciliation" "Analyst"
ensure_keycloak_user "11111111-1111-4111-8111-111111111107" "auditor" "auditor-password" "auditor@example.com" "LedgerOps" "Auditor"
ensure_keycloak_user "11111111-1111-4111-8111-111111111108" "viewer" "viewer-password" "viewer@example.com" "Read Only" "Viewer"
ensure_keycloak_user "11111111-1111-4111-8111-111111111109" "tenant-two-admin" "tenant-two-admin-password" "tenant-two-admin@example.com" "Northstar" "Administrator"

echo "Creating or verifying PostgreSQL demo data..."
run_psql \
  -v subject_slice1_human="$subject_slice1_human" \
  -v subject_tenant_admin="$subject_tenant_admin" \
  -v subject_merchant_admin="$subject_merchant_admin" \
  -v subject_operations_agent="$subject_operations_agent" \
  -v subject_risk_analyst="$subject_risk_analyst" \
  -v subject_reconciliation_analyst="$subject_reconciliation_analyst" \
  -v subject_auditor="$subject_auditor" \
  -v subject_viewer="$subject_viewer" \
  -v subject_tenant_two_admin="$subject_tenant_two_admin" <<'SQL'
BEGIN;

-- Three active Tenants and six Merchants give the tenant selector and scope
-- checks meaningful data without changing product behavior.
UPDATE tenancy.tenants
   SET name = 'Acme Commerce', default_currency = 'USD', default_locale = 'en-US'
 WHERE id = '00000000-0000-4000-8000-000000000001';

UPDATE merchant.merchants
   SET name = 'Acme Online', status = 'ACTIVE'
 WHERE id = '00000000-0000-4000-8000-000000000011'
   AND tenant_id = '00000000-0000-4000-8000-000000000001';

INSERT INTO tenancy.tenants (id, name, default_currency, default_locale, status, version, created_at, updated_at)
VALUES
    ('00000000-0000-4000-8000-000000000002', 'Northstar Payments', 'SAR', 'en-US', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('00000000-0000-4000-8000-000000000003', 'Desert Logistics', 'SAR', 'en-US', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:30:00Z', TIMESTAMPTZ '2026-08-01T08:30:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO merchant.merchants (id, tenant_id, name, status, version, created_at, updated_at)
VALUES
    ('00000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-000000000001', 'Acme Retail', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:10:00Z', TIMESTAMPTZ '2026-08-01T08:10:00Z'),
    ('00000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000002', 'Northstar Online', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:40:00Z', TIMESTAMPTZ '2026-08-01T08:40:00Z'),
    ('00000000-0000-4000-8000-000000000022', '00000000-0000-4000-8000-000000000002', 'Northstar Stores', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:45:00Z', TIMESTAMPTZ '2026-08-01T08:45:00Z'),
    ('00000000-0000-4000-8000-000000000031', '00000000-0000-4000-8000-000000000003', 'Desert Freight', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T09:00:00Z', TIMESTAMPTZ '2026-08-01T09:00:00Z'),
    ('00000000-0000-4000-8000-000000000032', '00000000-0000-4000-8000-000000000003', 'Desert Warehousing', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T09:05:00Z', TIMESTAMPTZ '2026-08-01T09:05:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenancy.tenant_configurations
    (tenant_id, version, allowed_currencies, default_locale, timezone, display_settings, created_at, actor_identity)
VALUES
    ('00000000-0000-4000-8000-000000000001', 1, ARRAY['USD','SAR'], 'en-US', 'Asia/Riyadh', '{"dateStyle":"medium","timeStyle":"short"}'::jsonb, TIMESTAMPTZ '2026-08-01T08:00:00Z', 'local-demo-seed'),
    ('00000000-0000-4000-8000-000000000002', 1, ARRAY['SAR','USD'], 'en-US', 'Asia/Riyadh', '{"dateStyle":"medium","timeStyle":"short"}'::jsonb, TIMESTAMPTZ '2026-08-01T08:40:00Z', 'local-demo-seed'),
    ('00000000-0000-4000-8000-000000000003', 1, ARRAY['SAR','USD'], 'en-US', 'Asia/Riyadh', '{"dateStyle":"medium","timeStyle":"short"}'::jsonb, TIMESTAMPTZ '2026-08-01T09:00:00Z', 'local-demo-seed')
ON CONFLICT (tenant_id, version) DO NOTHING;

INSERT INTO identity.application_users (id, issuer, subject, status, version, created_at, updated_at)
VALUES
    ('10000000-0000-4000-8000-000000000001', 'http://localhost:8180/realms/ledgerops', :'subject_slice1_human', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000002', 'http://localhost:8180/realms/ledgerops', :'subject_tenant_admin', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000003', 'http://localhost:8180/realms/ledgerops', :'subject_merchant_admin', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000004', 'http://localhost:8180/realms/ledgerops', :'subject_operations_agent', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000005', 'http://localhost:8180/realms/ledgerops', :'subject_risk_analyst', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000006', 'http://localhost:8180/realms/ledgerops', :'subject_reconciliation_analyst', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000007', 'http://localhost:8180/realms/ledgerops', :'subject_auditor', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000008', 'http://localhost:8180/realms/ledgerops', :'subject_viewer', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('10000000-0000-4000-8000-000000000009', 'http://localhost:8180/realms/ledgerops', :'subject_tenant_two_admin', 'ACTIVE', 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.tenant_memberships
    (id, application_user_id, tenant_id, status, is_initial, version, created_at, updated_at)
VALUES
    ('20000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000004', '10000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000005', '10000000-0000-4000-8000-000000000005', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000006', '10000000-0000-4000-8000-000000000006', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000007', '10000000-0000-4000-8000-000000000007', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000008', '10000000-0000-4000-8000-000000000008', '00000000-0000-4000-8000-000000000001', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('20000000-0000-4000-8000-000000000009', '10000000-0000-4000-8000-000000000009', '00000000-0000-4000-8000-000000000002', 'ACTIVE', false, 0, TIMESTAMPTZ '2026-08-01T08:00:00Z', TIMESTAMPTZ '2026-08-01T08:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.tenant_role_assignments (id, membership_id, role, scope_mode)
VALUES
    ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', 'TENANT_ADMIN', 'TENANT_WIDE'),
    ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002', 'TENANT_ADMIN', 'TENANT_WIDE'),
    ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000003', 'MERCHANT_ADMIN', 'MERCHANT_SET'),
    ('30000000-0000-4000-8000-000000000004', '20000000-0000-4000-8000-000000000004', 'OPERATIONS_AGENT', 'MERCHANT_SET'),
    ('30000000-0000-4000-8000-000000000005', '20000000-0000-4000-8000-000000000005', 'RISK_ANALYST', 'TENANT_WIDE'),
    ('30000000-0000-4000-8000-000000000006', '20000000-0000-4000-8000-000000000006', 'RECONCILIATION_ANALYST', 'TENANT_WIDE'),
    ('30000000-0000-4000-8000-000000000007', '20000000-0000-4000-8000-000000000007', 'AUDITOR', 'TENANT_WIDE'),
    ('30000000-0000-4000-8000-000000000008', '20000000-0000-4000-8000-000000000008', 'VIEWER', 'TENANT_WIDE'),
    ('30000000-0000-4000-8000-000000000009', '20000000-0000-4000-8000-000000000009', 'TENANT_ADMIN', 'TENANT_WIDE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.role_assignment_merchant_scopes (role_assignment_id, merchant_id)
VALUES
    ('30000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000011'),
    ('30000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000012'),
    ('30000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000011'),
    ('30000000-0000-4000-8000-000000000004', '00000000-0000-4000-8000-000000000012')
ON CONFLICT DO NOTHING;

-- Currency accounts used by the seeded completed payments.
INSERT INTO ledger.accounts (id, tenant_id, account_code, currency, status, created_at)
SELECT md5('ledgerops-demo-account:' || tenant_id::text || ':' || account_code || ':' || currency)::uuid,
       tenant_id, account_code, currency, 'ACTIVE', TIMESTAMPTZ '2026-08-01T08:00:00Z'
  FROM (VALUES
    ('00000000-0000-4000-8000-000000000001'::uuid),
    ('00000000-0000-4000-8000-000000000002'::uuid),
    ('00000000-0000-4000-8000-000000000003'::uuid)
  ) tenants(tenant_id)
  CROSS JOIN (VALUES ('USD'::char(3)), ('SAR'::char(3))) currencies(currency)
  CROSS JOIN (VALUES
    ('PROVIDER_CLEARING'), ('MERCHANT_PAYABLE'), ('CUSTOMER_RECEIVABLE'),
    ('PLATFORM_FEE_REVENUE'), ('REVERSAL_PAYABLE'), ('SETTLEMENT_RECEIVABLE')
  ) accounts(account_code)
ON CONFLICT (tenant_id, account_code, currency) DO NOTHING;

-- Thirty-six synthetic but coherent customer/payment records spread across
-- the three Tenants. Existing two demo Payments remain untouched.
WITH seed AS (
    SELECT n,
           CASE WHEN n <= 12 THEN '00000000-0000-4000-8000-000000000001'::uuid
                WHEN n <= 24 THEN '00000000-0000-4000-8000-000000000002'::uuid
                ELSE '00000000-0000-4000-8000-000000000003'::uuid END AS tenant_id,
           CASE WHEN n <= 12 AND n % 2 = 0 THEN '00000000-0000-4000-8000-000000000012'::uuid
                WHEN n <= 12 THEN '00000000-0000-4000-8000-000000000011'::uuid
                WHEN n <= 24 AND n % 2 = 0 THEN '00000000-0000-4000-8000-000000000022'::uuid
                WHEN n <= 24 THEN '00000000-0000-4000-8000-000000000021'::uuid
                WHEN n % 2 = 0 THEN '00000000-0000-4000-8000-000000000032'::uuid
                ELSE '00000000-0000-4000-8000-000000000031'::uuid END AS merchant_id
      FROM generate_series(1, 36) AS numbers(n)
)
INSERT INTO customer.customers (id, tenant_id, merchant_id, customer_reference, status, version, created_at, updated_at)
SELECT md5('ledgerops-demo-customer:' || n)::uuid, tenant_id, merchant_id,
       'demo-customer-' || lpad(n::text, 3, '0'), 'ACTIVE', 0,
       TIMESTAMPTZ '2026-08-01T10:00:00Z' + n * INTERVAL '4 hours',
       TIMESTAMPTZ '2026-08-01T10:00:00Z' + n * INTERVAL '4 hours'
  FROM seed
ON CONFLICT (id) DO NOTHING;

WITH seed AS (
    SELECT n,
           CASE WHEN n <= 12 THEN '00000000-0000-4000-8000-000000000001'::uuid
                WHEN n <= 24 THEN '00000000-0000-4000-8000-000000000002'::uuid
                ELSE '00000000-0000-4000-8000-000000000003'::uuid END AS tenant_id,
           CASE WHEN n <= 12 AND n % 2 = 0 THEN '00000000-0000-4000-8000-000000000012'::uuid
                WHEN n <= 12 THEN '00000000-0000-4000-8000-000000000011'::uuid
                WHEN n <= 24 AND n % 2 = 0 THEN '00000000-0000-4000-8000-000000000022'::uuid
                WHEN n <= 24 THEN '00000000-0000-4000-8000-000000000021'::uuid
                WHEN n % 2 = 0 THEN '00000000-0000-4000-8000-000000000032'::uuid
                ELSE '00000000-0000-4000-8000-000000000031'::uuid END AS merchant_id
      FROM generate_series(1, 36) AS numbers(n)
)
INSERT INTO payment.payments
    (id, tenant_id, merchant_id, customer_id, amount, currency,
     payment_method_category, idempotency_key, request_fingerprint,
     status, version, created_at, updated_at)
SELECT ('10000000-0000-4000-8000-' || lpad(to_hex(n), 12, '0'))::uuid,
       tenant_id, merchant_id, md5('ledgerops-demo-customer:' || n)::uuid,
       CASE WHEN n % 10 = 0 THEN 2200.00 + n
            WHEN n % 10 IN (4, 5) THEN 700.00 + (n * 5)
            ELSE 125.00 + (n * 37.00) END,
       CASE WHEN tenant_id = '00000000-0000-4000-8000-000000000001'::uuid THEN 'USD' ELSE 'SAR' END,
       CASE WHEN n % 3 = 0 THEN 'BANK_TRANSFER' ELSE 'CARD' END,
       'ledgerops-realistic-payment-' || lpad(n::text, 3, '0'),
       md5('ledgerops-payment-fingerprint:' || n) || md5('ledgerops-payment-fingerprint-2:' || n),
       CASE n % 10
           WHEN 1 THEN 'COMPLETED'
           WHEN 2 THEN 'COMPLETED'
           WHEN 3 THEN 'FAILED'
           WHEN 4 THEN 'RISK_REVIEW'
           WHEN 5 THEN 'RISK_REVIEW'
           WHEN 6 THEN 'PROCESSING'
           WHEN 7 THEN 'APPROVED'
           WHEN 8 THEN 'COMPLETED'
           WHEN 9 THEN 'FAILED'
           ELSE 'REJECTED'
       END,
       0,
       TIMESTAMPTZ '2026-08-01T10:00:00Z' + n * INTERVAL '4 hours',
       TIMESTAMPTZ '2026-08-01T10:00:00Z' + n * INTERVAL '4 hours'
  FROM seed
ON CONFLICT (id) DO NOTHING;

INSERT INTO payment.payment_attempts
    (id, tenant_id, payment_id, sequence, provider_id, provider_idempotency_key,
     initiated_at, merchant_id, customer_id, amount, currency,
     payment_method_category, request_intent_hash, attempt_subject_type, attempt_subject_id)
SELECT md5('ledgerops-demo-attempt:' || p.id)::uuid, p.tenant_id, p.id, 1,
       'SIMULATOR', 'payment:' || lower(p.id::text), p.created_at,
       p.merchant_id, p.customer_id, p.amount, p.currency,
       p.payment_method_category, p.request_fingerprint, 'PAYMENT', p.id
  FROM payment.payments p
 WHERE p.id::text LIKE '10000000-0000-4000-8000-%'
ON CONFLICT (id) DO NOTHING;

-- Provider attempt/evidence rows make Payment investigation useful while the
-- consumer processes remain disabled in the local demo startup command.
INSERT INTO provider.work
    (id, tenant_id, attempt_id, payment_id, work_type, status, provider_id,
     provider_idempotency_key, request_intent_hash, command_payload, due_at,
     lease_owner, lease_token, lease_expires_at, correlation_id, causation_id,
     created_at, updated_at, execution_count, last_request_id, last_error_code,
     attempt_sequence, transport_retry_count, traceparent, tracestate,
     scenario_profile_id, scenario_profile_version, scenario_snapshot,
     operation_type, operation_id)
SELECT md5('ledgerops-demo-provider-work:' || p.id)::uuid, p.tenant_id, a.id, p.id,
       'SUBMISSION',
       CASE WHEN p.status = 'PROCESSING' THEN 'WAITING_STATUS'
            WHEN p.status IN ('COMPLETED','FAILED') THEN 'COMPLETED'
            ELSE 'PENDING' END,
       'SIMULATOR', 'payment:' || lower(p.id::text), p.request_fingerprint,
       jsonb_build_object('paymentId', p.id, 'amount', p.amount, 'currency', p.currency)::text,
       p.created_at + INTERVAL '1 minute', NULL, NULL, NULL,
       md5('ledgerops-demo-correlation:' || p.id)::uuid,
       md5('ledgerops-demo-cause:' || p.id)::uuid,
       p.created_at, p.updated_at,
       CASE WHEN p.status IN ('COMPLETED','FAILED','PROCESSING') THEN 1 ELSE 0 END,
       CASE WHEN p.status IN ('COMPLETED','FAILED','PROCESSING') THEN md5('ledgerops-demo-request:' || p.id)::uuid ELSE NULL END,
       CASE WHEN p.status = 'FAILED' THEN 'PROVIDER_DECLINED' ELSE NULL END,
       1, 0, NULL, NULL, NULL, NULL, NULL, 'PAYMENT', p.id
  FROM payment.payments p
  JOIN payment.payment_attempts a ON a.tenant_id = p.tenant_id AND a.payment_id = p.id AND a.sequence = 1
 WHERE p.id::text LIKE '10000000-0000-4000-8000-%'
    OR p.id IN ('00000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000022')
ON CONFLICT (tenant_id, attempt_id, work_type) DO NOTHING;

INSERT INTO provider.interactions
    (interaction_id, tenant_id, work_id, attempt_id, payment_id, provider_id,
     work_type, request_id, request_body_hash, response_body_hash, http_status,
     communication_outcome, latency_millis, safe_error_code, started_at,
     completed_at, webhook_event_id, operation_type, operation_id)
SELECT md5('ledgerops-demo-provider-interaction:' || p.id)::uuid, p.tenant_id, w.id,
       a.id, p.id, 'SIMULATOR', 'SUBMISSION', md5('ledgerops-demo-provider-request:' || p.id)::uuid,
       p.request_fingerprint, md5('ledgerops-demo-provider-response:' || p.id) || md5('ledgerops-demo-provider-response-2:' || p.id),
       200, 'RESPONSE', CASE WHEN p.status = 'FAILED' THEN 820 ELSE 240 END, NULL,
       p.created_at, p.created_at + INTERVAL '1 second', NULL, 'PAYMENT', p.id
  FROM payment.payments p
  JOIN payment.payment_attempts a ON a.tenant_id = p.tenant_id AND a.payment_id = p.id AND a.sequence = 1
  JOIN provider.work w ON w.tenant_id = p.tenant_id AND w.attempt_id = a.id AND w.work_type = 'SUBMISSION'
 WHERE p.status IN ('COMPLETED','FAILED','PROCESSING')
   AND (p.id::text LIKE '10000000-0000-4000-8000-%'
        OR p.id IN ('00000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000022'))
ON CONFLICT (interaction_id) DO NOTHING;

INSERT INTO provider.results
    (evidence_id, tenant_id, interaction_id, work_id, attempt_id, payment_id,
     provider_id, provider_idempotency_key, provider_result_id, provider_reference,
     result_category, retry_disposition, provider_transaction_found,
     no_acceptance_proven, evidence_origin, observed_at, webhook_event_id,
     operation_type, operation_id)
SELECT md5('ledgerops-demo-provider-evidence:' || p.id)::uuid, p.tenant_id, i.interaction_id,
       w.id, a.id, p.id, 'SIMULATOR', 'payment:' || lower(p.id::text),
       md5('ledgerops-demo-provider-result:' || p.id)::uuid,
       'SIM-' || upper(right(replace(p.id::text, '-', ''), 12)),
       CASE WHEN p.status = 'COMPLETED' THEN 'SUCCESS'
            WHEN p.status = 'FAILED' THEN 'DECLINED'
            ELSE 'ACCEPTED' END,
       CASE WHEN p.status = 'PROCESSING' THEN 'STATUS_RECOVERY_REQUIRED' ELSE 'NOT_RETRYABLE' END,
       p.status IN ('COMPLETED','PROCESSING'), false, 'SUBMISSION_RESPONSE',
       p.created_at + INTERVAL '1 second', NULL, 'PAYMENT', p.id
  FROM payment.payments p
  JOIN payment.payment_attempts a ON a.tenant_id = p.tenant_id AND a.payment_id = p.id AND a.sequence = 1
  JOIN provider.work w ON w.tenant_id = p.tenant_id AND w.attempt_id = a.id AND w.work_type = 'SUBMISSION'
  JOIN provider.interactions i ON i.tenant_id = p.tenant_id AND i.work_id = w.id
 WHERE p.status IN ('COMPLETED','FAILED','PROCESSING')
   AND (p.id::text LIKE '10000000-0000-4000-8000-%'
        OR p.id IN ('00000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000022'))
ON CONFLICT (evidence_id) DO NOTHING;

INSERT INTO payment.accepted_final_provider_results
    (tenant_id, payment_id, attempt_id, provider_evidence_id, provider_result_id,
     final_category, provider_reference, applied_at)
SELECT p.tenant_id, p.id, a.id, r.evidence_id, r.provider_result_id,
       CASE WHEN p.status = 'COMPLETED' THEN 'SUCCESS' ELSE 'DECLINED' END,
       r.provider_reference, p.updated_at
  FROM payment.payments p
  JOIN payment.payment_attempts a ON a.tenant_id = p.tenant_id AND a.payment_id = p.id AND a.sequence = 1
  JOIN provider.work w ON w.tenant_id = p.tenant_id AND w.attempt_id = a.id AND w.work_type = 'SUBMISSION'
  JOIN provider.results r ON r.tenant_id = p.tenant_id AND r.work_id = w.id
 WHERE p.status IN ('COMPLETED','FAILED')
   AND (p.id::text LIKE '10000000-0000-4000-8000-%'
        OR p.id IN ('00000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000022'))
ON CONFLICT (tenant_id, payment_id) DO NOTHING;

-- One balanced Ledger transaction for each seeded completed Payment.
INSERT INTO ledger.transactions
    (id, tenant_id, source_type, source_id, compensates_transaction_id,
     posted_at, currency, entry_count, debit_total, credit_total)
SELECT md5('ledgerops-demo-ledger:' || p.id)::uuid, p.tenant_id, 'PAYMENT', p.id, NULL,
       p.created_at, p.currency, 2, p.amount, p.amount
  FROM payment.payments p
 WHERE p.status = 'COMPLETED'
   AND p.id::text LIKE '10000000-0000-4000-8000-%'
ON CONFLICT (tenant_id, source_type, source_id) DO NOTHING;

INSERT INTO ledger.entries
    (tenant_id, transaction_id, entry_index, account_id, direction, amount, currency)
SELECT p.tenant_id, t.id, 0, debit_account.id, 'DEBIT', p.amount, p.currency
  FROM payment.payments p
  JOIN ledger.transactions t ON t.tenant_id = p.tenant_id AND t.source_type = 'PAYMENT' AND t.source_id = p.id
  JOIN ledger.accounts debit_account ON debit_account.tenant_id = p.tenant_id
       AND debit_account.account_code = 'PROVIDER_CLEARING' AND debit_account.currency = p.currency
 WHERE p.status = 'COMPLETED' AND p.id::text LIKE '10000000-0000-4000-8000-%'
ON CONFLICT DO NOTHING;

INSERT INTO ledger.entries
    (tenant_id, transaction_id, entry_index, account_id, direction, amount, currency)
SELECT p.tenant_id, t.id, 1, credit_account.id, 'CREDIT', p.amount, p.currency
  FROM payment.payments p
  JOIN ledger.transactions t ON t.tenant_id = p.tenant_id AND t.source_type = 'PAYMENT' AND t.source_id = p.id
  JOIN ledger.accounts credit_account ON credit_account.tenant_id = p.tenant_id
       AND credit_account.account_code = 'MERCHANT_PAYABLE' AND credit_account.currency = p.currency
 WHERE p.status = 'COMPLETED' AND p.id::text LIKE '10000000-0000-4000-8000-%'
ON CONFLICT DO NOTHING;

-- Risk profiles and evaluations make the manual-review queue real rather
-- than a frontend placeholder.
INSERT INTO risk.risk_profiles (id, tenant_id, version, review_threshold, reject_threshold, active, created_at)
VALUES
    ('90000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 1, 50, 80, true, TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('90000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', 1, 50, 80, true, TIMESTAMPTZ '2026-08-01T08:00:00Z'),
    ('90000000-0000-4000-8000-000000000003', '00000000-0000-4000-8000-000000000003', 1, 50, 80, true, TIMESTAMPTZ '2026-08-01T08:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO risk.payment_amount_threshold_rules
    (id, tenant_id, profile_id, currency, amount_threshold, score_contribution, enabled)
SELECT md5('ledgerops-demo-risk-rule:' || tenant_id::text || ':500')::uuid, tenant_id, id, currency, 500.00, 60, true
  FROM (VALUES
    ('00000000-0000-4000-8000-000000000001'::uuid, '90000000-0000-4000-8000-000000000001'::uuid, 'USD'::char(3)),
    ('00000000-0000-4000-8000-000000000002'::uuid, '90000000-0000-4000-8000-000000000002'::uuid, 'SAR'::char(3)),
    ('00000000-0000-4000-8000-000000000003'::uuid, '90000000-0000-4000-8000-000000000003'::uuid, 'SAR'::char(3))
  ) profiles(tenant_id, id, currency)
ON CONFLICT (id) DO NOTHING;

INSERT INTO risk.payment_amount_threshold_rules
    (id, tenant_id, profile_id, currency, amount_threshold, score_contribution, enabled)
SELECT md5('ledgerops-demo-risk-rule:' || tenant_id::text || ':1500')::uuid, tenant_id, id, currency, 1500.00, 25, true
  FROM (VALUES
    ('00000000-0000-4000-8000-000000000001'::uuid, '90000000-0000-4000-8000-000000000001'::uuid, 'USD'::char(3)),
    ('00000000-0000-4000-8000-000000000002'::uuid, '90000000-0000-4000-8000-000000000002'::uuid, 'SAR'::char(3)),
    ('00000000-0000-4000-8000-000000000003'::uuid, '90000000-0000-4000-8000-000000000003'::uuid, 'SAR'::char(3))
  ) profiles(tenant_id, id, currency)
ON CONFLICT (id) DO NOTHING;

INSERT INTO risk.risk_evaluations
    (id, tenant_id, payment_id, profile_id, profile_version, uncapped_score, final_score, decision, evaluated_at)
SELECT md5('ledgerops-demo-risk-evaluation:' || p.id)::uuid, p.tenant_id, p.id,
       profile.id, 1,
       CASE WHEN p.amount >= 1500 THEN 85 WHEN p.amount >= 500 THEN 60 ELSE 10 END,
       LEAST(100, CASE WHEN p.amount >= 1500 THEN 85 WHEN p.amount >= 500 THEN 60 ELSE 10 END),
       CASE WHEN p.amount >= 1500 THEN 'REJECT' WHEN p.amount >= 500 THEN 'MANUAL_REVIEW' ELSE 'APPROVE' END,
       p.created_at + INTERVAL '5 minutes'
  FROM payment.payments p
  JOIN risk.risk_profiles profile ON profile.tenant_id = p.tenant_id AND profile.active
 WHERE p.id::text LIKE '10000000-0000-4000-8000-%'
ON CONFLICT (tenant_id, payment_id) DO NOTHING;

INSERT INTO risk.evaluated_rule_results
    (tenant_id, evaluation_id, rule_id, rule_type, currency, amount_threshold,
     configured_contribution, triggered, applied_contribution)
SELECT evaluation.tenant_id, evaluation.id, rule.id, 'PAYMENT_AMOUNT_THRESHOLD', rule.currency,
       rule.amount_threshold, rule.score_contribution,
       p.amount >= rule.amount_threshold,
       CASE WHEN p.amount >= rule.amount_threshold THEN rule.score_contribution ELSE 0 END
  FROM risk.risk_evaluations evaluation
  JOIN payment.payments p ON p.tenant_id = evaluation.tenant_id AND p.id = evaluation.payment_id
  JOIN risk.payment_amount_threshold_rules rule ON rule.tenant_id = evaluation.tenant_id
 WHERE p.id::text LIKE '10000000-0000-4000-8000-%'
ON CONFLICT DO NOTHING;

INSERT INTO risk.risk_reviews
    (id, tenant_id, payment_id, evaluation_id, status, assigned_analyst_id,
     priority, sla_version, created_at, due_at, decision, decision_reason,
     case_id, decided_at, version, merchant_id)
SELECT md5('ledgerops-demo-risk-review:' || evaluation.payment_id)::uuid,
       evaluation.tenant_id, evaluation.payment_id, evaluation.id, 'UNASSIGNED', NULL,
       evaluation.final_score, 1, evaluation.evaluated_at,
       evaluation.evaluated_at + INTERVAL '24 hours', NULL, NULL, NULL, NULL, 0, p.merchant_id
  FROM risk.risk_evaluations evaluation
  JOIN payment.payments p ON p.tenant_id = evaluation.tenant_id AND p.id = evaluation.payment_id
 WHERE evaluation.decision = 'MANUAL_REVIEW'
ON CONFLICT (tenant_id, payment_id) DO NOTHING;

-- A small case queue with different operational states.
INSERT INTO casework.cases
    (id, tenant_id, source_category, source_id, related_payment_id, severity, due_at,
     status, owner_id, resolution, resolution_note, corrective_action_required,
     corrective_action_completed, created_at, updated_at)
SELECT md5('ledgerops-demo-case:' || review.id)::uuid, review.tenant_id, 'RISK_REVIEW', review.id,
       review.payment_id,
       CASE WHEN row_number() OVER (PARTITION BY review.tenant_id ORDER BY review.id) % 3 = 0 THEN 'HIGH' ELSE 'MEDIUM' END,
       review.due_at, 'OPEN', '10000000-0000-4000-8000-000000000005'::uuid,
       NULL, NULL, false, false, review.created_at, review.created_at
  FROM risk.risk_reviews review
 WHERE review.id IN (
    md5('ledgerops-demo-risk-review:' || '10000000-0000-4000-8000-000000000004')::uuid,
    md5('ledgerops-demo-risk-review:' || '10000000-0000-4000-8000-000000000005')::uuid,
    md5('ledgerops-demo-risk-review:' || '10000000-0000-4000-8000-000000000014')::uuid
 )
ON CONFLICT (id) DO NOTHING;

INSERT INTO casework.case_history
    (case_id, tenant_id, sequence, event_type, from_status, to_status, actor_id, reason, occurred_at)
SELECT c.id, c.tenant_id, 1, 'CREATED', NULL, 'OPEN',
       '10000000-0000-4000-8000-000000000005'::uuid,
       'Local demo case created from a manual risk review.', c.created_at
  FROM casework.cases c
 WHERE c.source_category = 'RISK_REVIEW'
ON CONFLICT DO NOTHING;

INSERT INTO casework.case_notes (id, tenant_id, case_id, author_id, note, created_at)
SELECT md5('ledgerops-demo-case-note:' || c.id)::uuid, c.tenant_id, c.id,
       '10000000-0000-4000-8000-000000000005'::uuid,
       'Review payment context, provider evidence, and customer history before deciding.', c.created_at + INTERVAL '15 minutes'
  FROM casework.cases c
 WHERE c.source_category = 'RISK_REVIEW'
ON CONFLICT (id) DO NOTHING;

-- Provider history is global in the approved schema and is visible to the
-- Provider operations page through the existing health API.
INSERT INTO provider.health_evaluations
    (evaluation_id, provider_id, policy_id, policy_version, health_version, state,
     completed_calls, successful_communications, timeout_count, system_error_count,
     p95_latency_millis, circuit_state, window_started_at, window_ended_at, evaluated_at)
VALUES
    ('a0000000-0000-4000-8000-000000000001', 'SIMULATOR', '00000000-0000-0000-0000-000000000027', 1, 1, 'HEALTHY', 42, 41, 1, 0, 280, 'CLOSED', TIMESTAMPTZ '2026-08-10T00:00:00Z', TIMESTAMPTZ '2026-08-10T00:05:00Z', TIMESTAMPTZ '2026-08-10T00:05:00Z'),
    ('a0000000-0000-4000-8000-000000000002', 'SIMULATOR', '00000000-0000-0000-0000-000000000027', 1, 2, 'DEGRADED', 38, 31, 5, 2, 3400, 'OPEN', TIMESTAMPTZ '2026-08-11T00:00:00Z', TIMESTAMPTZ '2026-08-11T00:05:00Z', TIMESTAMPTZ '2026-08-11T00:05:00Z'),
    ('a0000000-0000-4000-8000-000000000003', 'SIMULATOR', '00000000-0000-0000-0000-000000000027', 1, 3, 'HEALTHY', 57, 56, 1, 0, 310, 'CLOSED', TIMESTAMPTZ '2026-08-12T00:00:00Z', TIMESTAMPTZ '2026-08-12T00:05:00Z', TIMESTAMPTZ '2026-08-12T00:05:00Z')
ON CONFLICT (evaluation_id) DO NOTHING;

INSERT INTO provider.health_current (provider_id, evaluation_id, health_version, state, updated_at)
VALUES ('SIMULATOR', 'a0000000-0000-4000-8000-000000000003', 3, 'HEALTHY', TIMESTAMPTZ '2026-08-12T00:05:00Z')
ON CONFLICT (provider_id) DO NOTHING;

-- One completed-with-discrepancies batch/run per of the first two Tenants so
-- the reconciliation page has real history to inspect.
INSERT INTO reconciliation.settlement_batch_families
    (family_id, tenant_id, provider_id, provider_batch_reference,
     settlement_period_start, settlement_period_end, created_at)
VALUES
    ('b0000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'SIMULATOR', 'acme-2026-08-10', DATE '2026-08-10', DATE '2026-08-10', TIMESTAMPTZ '2026-08-11T04:00:00Z'),
    ('b0000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', 'SIMULATOR', 'northstar-2026-08-10', DATE '2026-08-10', DATE '2026-08-10', TIMESTAMPTZ '2026-08-11T04:10:00Z')
ON CONFLICT (family_id) DO NOTHING;

INSERT INTO reconciliation.batch_family_controls (batch_family_id, tenant_id, created_at)
VALUES
    ('b0000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-08-11T04:00:00Z'),
    ('b0000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', TIMESTAMPTZ '2026-08-11T04:10:00Z')
ON CONFLICT (batch_family_id) DO NOTHING;

INSERT INTO reconciliation.settlement_batch_versions
    (batch_version_id, family_id, raw_file_sha256, object_key, byte_size, status,
     supersedes_batch_version_id, total_rows, valid_rows, invalid_rows,
     structural_error_code, created_by_application_user_id, created_at, updated_at)
VALUES
    ('b1000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001', repeat('a', 64), 'demo/acme-2026-08-10.csv', 4096, 'COMPLETED_WITH_DISCREPANCIES', NULL, 8, 7, 1, NULL, '10000000-0000-4000-8000-000000000006'::uuid, TIMESTAMPTZ '2026-08-11T04:00:00Z', TIMESTAMPTZ '2026-08-11T04:00:00Z'),
    ('b1000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', repeat('b', 64), 'demo/northstar-2026-08-10.csv', 5120, 'COMPLETED', NULL, 6, 6, 0, NULL, '10000000-0000-4000-8000-000000000006'::uuid, TIMESTAMPTZ '2026-08-11T04:10:00Z', TIMESTAMPTZ '2026-08-11T04:10:00Z')
ON CONFLICT (batch_version_id) DO NOTHING;

INSERT INTO reconciliation.reconciliation_snapshots
    (snapshot_id, tenant_id, batch_family_id, batch_version_id, run_number, rules_version,
     source_cutoff, snapshot_status, snapshot_sha256, captured_record_count,
     captured_fact_count, created_at, completed_at, failure_reason)
VALUES
    ('b2000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001', 'b1000000-0000-4000-8000-000000000001', 1, 'release-0.3-demo', TIMESTAMPTZ '2026-08-11T03:59:00Z', 'BUILDING', NULL, 8, 8, TIMESTAMPTZ '2026-08-11T04:00:00Z', NULL, NULL),
    ('b2000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', 'b1000000-0000-4000-8000-000000000002', 1, 'release-0.3-demo', TIMESTAMPTZ '2026-08-11T03:59:00Z', 'BUILDING', NULL, 6, 6, TIMESTAMPTZ '2026-08-11T04:10:00Z', NULL, NULL)
ON CONFLICT (snapshot_id) DO NOTHING;

UPDATE reconciliation.reconciliation_snapshots
   SET snapshot_status = 'COMPLETE', snapshot_sha256 = repeat('c', 64), completed_at = created_at + INTERVAL '20 seconds'
 WHERE snapshot_id IN ('b2000000-0000-4000-8000-000000000001', 'b2000000-0000-4000-8000-000000000002')
   AND snapshot_status = 'BUILDING';

INSERT INTO reconciliation.reconciliation_runs
    (run_id, tenant_id, batch_family_id, batch_version_id, snapshot_id, run_number,
     rules_version, source_cutoff, status, matched_count, unmatched_count,
     discrepancy_count, created_at, started_at, terminal_at, failure_reason)
VALUES
    ('b3000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001', 'b1000000-0000-4000-8000-000000000001', 'b2000000-0000-4000-8000-000000000001', 1, 'release-0.3-demo', TIMESTAMPTZ '2026-08-11T03:59:00Z', 'COMPLETED_WITH_DISCREPANCIES', 6, 1, 1, TIMESTAMPTZ '2026-08-11T04:00:30Z', TIMESTAMPTZ '2026-08-11T04:01:00Z', TIMESTAMPTZ '2026-08-11T04:02:00Z', NULL),
    ('b3000000-0000-4000-8000-000000000002', '00000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', 'b1000000-0000-4000-8000-000000000002', 'b2000000-0000-4000-8000-000000000002', 1, 'release-0.3-demo', TIMESTAMPTZ '2026-08-11T03:59:00Z', 'COMPLETED', 6, 0, 0, TIMESTAMPTZ '2026-08-11T04:10:30Z', TIMESTAMPTZ '2026-08-11T04:11:00Z', TIMESTAMPTZ '2026-08-11T04:12:00Z', NULL)
ON CONFLICT (run_id) DO NOTHING;

INSERT INTO reconciliation.current_reconciliation_runs
    (tenant_id, batch_family_id, run_id, promoted_at, updated_at)
VALUES
    ('00000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001', 'b3000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-08-11T04:03:00Z', TIMESTAMPTZ '2026-08-11T04:03:00Z'),
    ('00000000-0000-4000-8000-000000000002', 'b0000000-0000-4000-8000-000000000002', 'b3000000-0000-4000-8000-000000000002', TIMESTAMPTZ '2026-08-11T04:13:00Z', TIMESTAMPTZ '2026-08-11T04:13:00Z')
ON CONFLICT (tenant_id, batch_family_id) DO NOTHING;

COMMIT;
SQL

echo "Local realistic demo data is ready."
echo "Created/verified: 3 Tenants, 6 Merchants, 9 human users, 8 role memberships, 38 Payments, risk reviews, cases, health history, Ledger postings, and reconciliation history."
echo "Reporting is intentionally not written here; run the documented Core rebuild for each Tenant after Core starts."
