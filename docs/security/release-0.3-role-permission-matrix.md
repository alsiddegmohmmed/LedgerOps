# Release 0.3 role and permission matrix

Status: Approved normative security baseline  
Authority: Product Definition v1.7; Technical Specification v1.7; ADR-022  

Application services authorize permissions, Tenant/Merchant scope, resource ownership, and business state. UI visibility is not authority.

## Closed permission catalogue

### Platform permissions

```text
platform:tenant-create
platform:tenant-activate
platform:tenant-suspend
platform:tenant-reactivate
platform:tenant-archive
platform:provider-scenario-manage
platform:health-read
platform:audit-read
support:tenant-read
```

### Tenant/Merchant permissions

```text
tenant:read
tenant:configure
tenant:membership-manage
tenant:role-manage
merchant:create
merchant:read
merchant:configure
merchant:suspend
credential:manage
payment:read
payment:note-add
payment:retry
reversal:request
reversal:retry
risk:read
risk:review-assign
risk:review-decide
risk:configuration-manage
provider:read
provider:health-read
settlement:upload
reconciliation:read
reconciliation:run
reconciliation:promote
case:read
case:assign
case:update
case:resolve
case:close
correction:request
ledger:read
audit:read
report:read
report:export
notification:read
webhook:endpoint-manage
webhook:test-trigger
```

### Service credential permission

```text
payment:create
```

Release 0.3 has no direct per-user permission grants, deny rules, user-defined roles, or generic policy language. Human permissions come only from the closed role mapping below. A user may hold several role assignments; effective permissions are the scoped union of active assignments.

## Role mapping

`PLATFORM_ADMIN` is a Platform assignment, never a Tenant membership role.

| Role | Required scope | Permissions | Resource restrictions |
|---|---|---|---|
| `PLATFORM_ADMIN` | Platform | all `platform:*` and `support:tenant-read` | Platform audit read covers Platform lifecycle/security evidence only. Tenant business records still require explicit support access, which is read-only, expiring, and audited; no Tenant business mutation. |
| `TENANT_ADMIN` | `TENANT_WIDE` | `tenant:read`, `tenant:configure`, `tenant:membership-manage`, `tenant:role-manage`, `merchant:create`, `merchant:read`, `merchant:configure`, `merchant:suspend`, `credential:manage`, `payment:read`, `risk:read`, `risk:configuration-manage`, `provider:read`, `provider:health-read`, `reconciliation:read`, `case:read`, `ledger:read`, `audit:read`, `report:read`, `report:export`, `notification:read`, `webhook:endpoint-manage`, `webhook:test-trigger` | Administration/read authority only; assign an operational role for Payment, Risk-review, Reversal-retry, Case-resolution, or correction actions. |
| `MERCHANT_ADMIN` | non-empty `MERCHANT_SET` | `tenant:read`, `tenant:membership-manage`, `tenant:role-manage`, `merchant:read`, `merchant:configure`, `credential:manage`, `payment:read`, `payment:note-add`, `reversal:request`, `risk:read`, `provider:read`, `provider:health-read`, `case:read`, `ledger:read`, `report:read`, `report:export`, `notification:read`, `webhook:endpoint-manage`, `webhook:test-trigger` | Only assigned Merchants; may invite/assign only approved Merchant-scoped roles inside the same or narrower Merchant set. |
| `OPERATIONS_AGENT` | `TENANT_WIDE` or non-empty `MERCHANT_SET` | `tenant:read`, `merchant:read`, `payment:read`, `payment:note-add`, `payment:retry`, `reversal:retry`, `provider:read`, `provider:health-read`, `case:read`, `case:assign`, `case:update`, `ledger:read`, `notification:read` | Cannot resolve/close Risk or Reconciliation cases, request correction, change Risk configuration, or request a new Reversal. |
| `RISK_ANALYST` | `TENANT_WIDE` or non-empty `MERCHANT_SET` | `tenant:read`, `merchant:read`, `payment:read`, `risk:read`, `risk:review-assign`, `risk:review-decide`, `case:read`, `case:assign`, `case:update`, `case:resolve`, `case:close`, `notification:read`; additionally `risk:configuration-manage` only for `TENANT_WIDE` | `case:resolve`/`case:close` apply only to `RISK_REVIEW` cases. Merchant-scoped assignment cannot change Tenant Risk configuration. |
| `RECONCILIATION_ANALYST` | `TENANT_WIDE` | `tenant:read`, `merchant:read`, `payment:read`, `provider:read`, `provider:health-read`, `settlement:upload`, `reconciliation:read`, `reconciliation:run`, `reconciliation:promote`, `case:read`, `case:assign`, `case:update`, `case:resolve`, `case:close`, `correction:request`, `ledger:read`, `report:read`, `report:export`, `notification:read` | Case resolution/correction applies only to Reconciliation discrepancy cases. |
| `AUDITOR` | `TENANT_WIDE` or non-empty `MERCHANT_SET` | `tenant:read`, `merchant:read`, `payment:read`, `risk:read`, `provider:read`, `provider:health-read`, `reconciliation:read`, `case:read`, `ledger:read`, `audit:read`, `report:read`, `report:export`, `notification:read` | Read/export only. |
| `VIEWER` | `TENANT_WIDE` or non-empty `MERCHANT_SET` | `tenant:read`, `merchant:read`, `payment:read`, `risk:read`, `provider:read`, `provider:health-read`, `reconciliation:read`, `case:read`, `ledger:read`, `report:read`, `notification:read` | Read only; no audit search or export. |
| `INTEGRATION_DEVELOPER` | non-empty `MERCHANT_SET` | `tenant:read`, `merchant:read`, `credential:manage`, `payment:read`, `provider:read`, `webhook:endpoint-manage`, `webhook:test-trigger`, `notification:read` | Only assigned Merchants and their credentials/webhook evidence. |
| Active sandbox credential | exactly one Tenant and one Merchant | `payment:create` | Machine identity only; no human UI, administration, cross-Merchant scope, or other API operation. |

## Role assignment authority

- A `TENANT_ADMIN` may assign any Tenant role using only a scope mode permitted for that role and only inside the same Tenant.
- A `MERCHANT_ADMIN` may invite/assign `MERCHANT_ADMIN`, `OPERATIONS_AGENT`, `RISK_ANALYST`, `AUDITOR`, `VIEWER`, or `INTEGRATION_DEVELOPER` only with a non-empty Merchant set contained in the administrator's own Merchant set.
- A `MERCHANT_ADMIN` cannot assign `TENANT_ADMIN`, `RECONCILIATION_ANALYST`, `PLATFORM_ADMIN`, Tenant-wide scope, or Tenant-wide Risk configuration authority.
- Only Platform onboarding or an existing `TENANT_ADMIN` may assign `TENANT_ADMIN`.
- No Tenant role can assign `PLATFORM_ADMIN`.
- Suspending/revoking the last active `TENANT_ADMIN` is rejected unless the Platform operation simultaneously suspends or archives the Tenant.

## Sensitive-action additions

Permission alone is insufficient where the Product requires confirmation/reason. Reversal request/retry, Tenant/Merchant suspension, credential revocation/rotation, Risk configuration, Provider scenario change, Reconciliation promotion/rerun, correction, Case resolution/closure/reopen, report export, and support entry also enforce current business state, explicit confirmation, reason where applicable, and immutable audit.
