# Release 0.3 requirement and decision matrix

Status: Approved planning baseline  
Authority: Product Definition v1.7, Technical Specification v1.7, ADR-022 through ADR-027  
Purpose: expose ownership, state, identity, transaction, authorization, recovery, verification, and slice placement before implementation.

| Requirement | Owner | Locked behavior/identity | Authorization/failure | Verification | Slice |
|---|---|---|---|---|---|
| TEN-01 | Administration + Tenancy/Merchant/Identity | one transaction creates PENDING Tenant, active initial Merchant, invited Tenant Admin, audit; Platform activation checks prerequisites | Platform lifecycle permission; no external call in transaction | rollback/concurrency, prerequisites, lifecycle audit | 2 |
| TEN-02 | Administration + Tenancy/Merchant/Risk | versioned Tenant config; Merchant ACTIVE/SUSPENDED; immutable Tenant ownership | exact role matrix/scope; suspended Merchant blocks new activity | version/history, scope denial, last-active behavior | 2/5 |
| TEN-03 | Identity/Administration | separate invitation/membership; seven-day hashed one-time token; REVOKED terminal | exact assignment-authority matrix; last Tenant Admin invariant | expiry/revoke/accept races and escalation denial | 2 |
| TEN-04 | Identity + Keycloak | deterministic one-Tenant/one-Merchant client; exact credential transitions; secret disclosed once | local ACTIVE/Tenant/Merchant each request; service permission payment:create | crash/retry/lost response/rotate/revoke | 2 |
| IAM-01 | Identity/BFF | deterministic Platform Admin bootstrap; Keycloak OIDC/OAuth; opaque server-side session; ACTIVE/DEACTIVATED app user | non-disclosing 401; cookie/CSRF; no browser token | issuer/audience/time, Redis loss, login/logout | 1 |
| IAM-02 | Identity + owning module | closed roles/permissions; no direct grants; scope union; resource/business-state checks | application layer definitive | exact role matrix per API | 1-11 |
| IAM-03 | Identity | explicit Tenant selector; request-scoped current PostgreSQL context | revalidate membership/scope/status/resource every request | multi-tab, stale/revoked, cross-Tenant/Merchant | 1 |
| IAM-04 | Owning module + Audit | sensitive action confirmation/reason/current state/audit | permission is necessary but insufficient | full negative/action matrix | 2-10 |
| PAY-01 | Payment/Identity | Tenant/Merchant derived from ACTIVE credential; machine permission payment:create | credential/Tenant/Merchant current state | authenticated create/replay/conflict/concurrency | 2 |
| PAY-02 | Payment | tenantId+idempotencyKey unchanged; Merchant remains content | conflicting content 409 | existing and authenticated replay/concurrency | preserved/2 |
| PAY-03 | Payment/Ledger | ADR-016/020/021 lifecycle and atomic completion unchanged; PaymentLifecycleChanged per new transition | no arbitrary status endpoint | transition/rollback/replay/event duplicate | preserved/3-6 |
| PAY-04 | Reporting/query composition | source APIs plus source-message-id projection and honest baseline marker | current read/scope on query and notification links | rebuild, source comparison, one-screen explanation | 3/10 |
| PAY-05 | Payment/Reporting | required filters and stable keyset pagination | scope on every filter | concurrent pagination/isolation | 3 |
| PAY-06 | Payment/Provider/Ledger | one full Reversal; one stable source/compensation | reversal:request, confirmation/reason | request/attempt/result/accounting concurrency | 6 |
| PAY-07 | Payment | append-only note; edit creates history; no delete | payment:note-add | immutability/deactivated author | 3 |
| PAY-08 | Payment/Provider/Ledger | exact Reversal lifecycle/attempts/provider evidence/two-entry compensation | request/retry roles and state | lifecycle/replay/failpoints/UI evidence | 6 |
| RSK-01 | Risk | ADR-018 deterministic evaluation/evidence unchanged | current Tenant profile | regression | preserved |
| RSK-02 | Risk/Payment | APPROVE/MANUAL_REVIEW/REJECT unchanged; MANUAL_REVIEW creates one RiskReview | no fallback | regression plus review creation/backfill | preserved/4 |
| RSK-03 | Risk | one RiskReview per manual-review Payment; assignment/priority/SLA | risk review permissions/scope | queue/backfill/assignment races | 4 |
| RSK-04 | Payment/Risk/Casework | final APPROVE/REJECT/ESCALATE; ESCALATE one stable Case; Case resolution applies Payment transition | assigned Risk Analyst; source-specific Case resolution | decision/case/effect atomicity | 4 |
| RSK-05 | Risk/Administration | versioned Tenant-wide active profile/rules | Tenant Admin or Tenant-wide Risk Analyst only | validation/version/current-profile race | 5 |
| PRV-01 | Provider/Administration/Simulator | versioned GLOBAL/TENANT/PAYMENT scenario; precedence and first-attempt pinning; signed v2 | Platform scenario permission/audit | validation/precedence/pinning/history/content conflict | 5 |
| PRV-02 | Payment/Provider | immutable PAYMENT/REVERSAL attempt subject and Provider evidence | scoped read | migration/sequence/hash/duplicates | 3/5/6 |
| PRV-03 | Provider/Reporting | immutable webhook receipt/verification/timeline facts | scoped read | duplicate/out-of-order/conflict | 3/5/6 |
| PRV-04 | Provider/Payment | retry-now accelerates WAITING_RETRY_REQUEST; Reversal retry uses ReversalRetryApplication | current safe evidence/limit/scope | scheduler/operator and Reversal retry races | 5/6 |
| PRV-05 | Provider/Reporting | durable versioned health state UNKNOWN/HEALTHY/DEGRADED/UNAVAILABLE | Provider read/health scope | exact policy boundaries/history/event changes | 5/10 |
| LED-01 | Ledger | balance invariant plus closed Payment/Reversal/settlement/correction templates | caller cannot select arbitrary entries | exact template/DB balance | preserved/6/8/9 |
| LED-02 | Ledger | six ACTIVE-only account codes; REVERSAL_PAYABLE reserved | no account lifecycle UI | regression and allowed-template use | preserved |
| LED-03 | Ledger/query | stable source/compensation navigation and exact replay | ledger read scope | bidirectional/source-shape tests | 3/6/8/9 |
| LED-04 | Ledger + owner workflows | immutable history; one exact source-specific compensation | no general journal | triggers, eligibility, duplicate target | preserved/9 |
| LED-05 | Ledger/query | balances/date-bounded statements from immutable entries | ledger read/scope | totals, bounds, pagination | 3 |
| REC-01 | Reconciliation/object storage | family unique Tenant/Provider/reference/period; version by hash; canonical record by Provider key/content plus occurrence by version/row | settlement:upload; no silent partial acceptance | duplicate/corrected/duplicate-row/outage/restart | 7 |
| REC-02 | Reconciliation/Ledger | immutable snapshot/run/results; current exact matches create stable instructions/applications | reconciliation:run/current pointer | cutoff/restart/100k/accounting | 8 |
| REC-03 | Reconciliation | closed discrepancy catalogue incl. duplicate occurrence and reversal-without-payment-settlement | no posting for ambiguous/incompatible | one fixture/category | 8 |
| REC-04 | Reconciliation/query | immutable detail/source links/status history outside Payment | reconciliation:read | history after Case/correction and navigation | 8 |
| REC-05 | Reconciliation | BatchFamilyControl + current pointer; block invalidating uncompensated posting | reconciliation:promote + audit | promotion/posting/correction races | 8/9 |
| CAS-01 | Casework | source category RISK_REVIEW/RECONCILIATION_DISCREPANCY; one Case/source | source command and current scope | duplicate command/linking | 4/8 |
| CAS-02 | Casework | exact Case lifecycle, assignment/reassignment, append-only collaboration | source-scoped case permissions | lifecycle/concurrency/history | 4/8 |
| CAS-03 | Casework | exact source-specific resolution catalog; approved correction requires completed request | source-specific resolver and reason | invalid category/effect gating | 4/9 |
| CAS-04 | Casework | resolve/close/reopen preserves history; required Payment/correction effect first | case:resolve/close and confirmation | atomic effect/closure/reopen | 4/9 |
| CAS-05 | Casework | severity/owner/due/overdue from injected Clock/versioned SLA | scoped query | SLA boundary/filter/scheduler | 4/10 |
| OPS-01 | Reporting/UI | rebuildable source-reconciled dashboard and links | current Tenant/Merchant | metric/source equality | 10 |
| OPS-02 | Reporting/SSE | persisted monotonic Tenant stream, Last-Event-ID/resync, LIVE/RECONNECTING/STALE | auth on connect/reconnect/Tenant switch | disconnect/replay/resync/switch | 10 |
| OPS-03 | Reporting/UI | actionable queues from authoritative facts/projections | current scope/action revalidation | navigation/action denial | 4/5/10 |
| OPS-04 | Reporting/UI | business-language timeline, stable source facts, actor/outcome | scoped read | terminology/duplicate/rebuild/non-specialist review | 3-10 |
| NTF-01 | Notification | unique recipient+sourceMessage+type, durable breach scheduling, mutable readAt only | current recipient and link authority | duplicate/reorder/revoke/read | 10 |
| NTF-02 | Deferred | Product SHOULD | not release-blocking | scope audit | deferred |
| NTF-03 | Deferred | Product SHOULD; DEV-02 delivery history is separate | not release-blocking | scope audit | deferred |
| AUD-01 | Audit | append-only security/config/financial/case/admin actions | owning transaction where local | atomicity and DB mutation block | 1-10 |
| AUD-02 | Audit/query | actor/Tenant/action/entity/date/result/correlation filters | audit:read/scope | isolation/pagination | 3 |
| AUD-03 | Reporting | source-reconciled payment/risk/provider/reconciliation/case/ledger reports | report:read | totals/filter consistency | 10 |
| AUD-04 | Reporting | bounded audited CSV with formula neutralisation/no secrets | report:export | isolation/limits/content | 10 |
| DEV-01 | API/docs | auth/idempotency/errors/HMAC/scenario v2 contracts | public docs no secrets | developer walkthrough | 2/5/6/10 |
| DEV-02 | Notification | encrypted signed SSRF-safe webhook and durable attempts | assigned Integration Developer | encryption/SSRF/HMAC/retry/revoke | 5 |
| DEV-03 | Administration/demo | deterministic scenario launcher/reset/expected evidence | seeded demo permissions | scenario final-state assertions | 10/11 |
| DEV-04 | Demo | synthetic complete data only | no real data | seed/privacy/reset | 10 |
| LOC-01 | Operations Web | stable codes with English/Arabic parity | same auth in both | bilingual workflows | 10 |
| LOC-02 | Operations Web | RTL and mixed identifiers/amount/time | same auth | RTL visual/interaction fixtures | 10 |
| LOC-03 | Operations Web | keyboard/focus/semantics/contrast/non-colour | same auth | automated plus manual keyboard | 3-11 |
| BR-01 | All business modules | exactly one Tenant or explicit platform-wide record | DB/app scope | all tenant tables/queries | 1-11 |
| BR-02 | Payment/Ledger/Reconciliation | explicit currency/precision | no float | Money and file/template tests | preserved/6-9 |
| BR-03 | Ledger/owner workflows | correction through new compensation only | no mutation | trigger/compensation | preserved/6/9 |
| BR-04 | Payment | tenant-wide idempotency | conflict on changed content | regression | preserved/2 |
| BR-05 | Payment/Provider | duplicate Provider fact one result/effect | final conflict preserved | duplicate/concurrent | preserved/6 |
| BR-06 | Provider/Payment | stop when unsafe/limit; no blind retry | exact evidence | retry/recovery limits | preserved/5/6 |
| BR-07 | Identity/Audit | attributable actor for human decisions | current identity | audit matrices | 1-10 |
| BR-08 | Identity/owning module | no action beyond role/scope | exact role matrix | negative matrices | 1-11 |
| BR-09 | Casework | controlled resolution/explanation/effect | source resolver | closure gates | 4/9 |
| BR-10 | Tenancy/business modules | suspended Tenant readable, no new user activity; committed recovery continues | status each request | suspend/recovery races | 2-11 |
| BR-11 | Identity/Audit | user deactivated, never deleted; authorship retained | DEACTIVATED denied | history/deactivate tests | 1-3 |
| BR-12 | All | injected time/UTC storage/locale display | no client time authority | boundary/timezone tests | 1-11 |
| BR-13 | Payment/Reconciliation/Provider | separate histories/status dimensions | no collapsed field | schema/API tests | 3/6/8 |
| BR-14 | Payment/Ledger | only completed full Reversal changes Payment/financial effect | exact success evidence | failpoints/replay | 6 |
| BR-15 | Reconciliation | immutable rerun and one current pointer | promotion lock | rerun/pointer races | 8 |
| BR-16 | Identity/Administration | Platform no implicit Tenant business authority | explicit support read only | support negative matrix | 1/2 |
| BR-17 | Reconciliation/Ledger | stable canonical-record instruction/application and exact Payment/Reversal settlement templates | current/sequence lock | rerun/restart/accounting | 8 |
| BR-18 | Casework/Ledger | exact inverse only of invalidated settlement adjustment | correction permission/reason | eligibility/lock/replay | 9 |

## Cross-cutting closure

| Concern | Decision | Gate |
|---|---|---|
| Module graph | ADR-025 Administration top-level orchestration; Identity no business dependencies; Ledger protected queries and Reporting read-only edges remain acyclic | Modulith/ArchUnit |
| Messaging | ADR-025/027 closed producer/topic/message/dedup/partition/consumer catalogue | schemas/outbox/inbox/compatibility |
| External boundaries | Keycloak, Provider, object storage, merchant webhook outside DB transactions | transaction probes/recovery |
| Financial identity | Payment, Reversal, SettlementPostingInstruction/Application, CorrectionRequest exact and unique | PostgreSQL/replay/concurrency |
| Current-run concurrency | BatchFamilyControl first lock for promotion/posting/correction | coordinated/deadlock tests |
| Derived experience | Reporting/Notification/SSE rebuildable from source messages; not truth | duplicate/reorder/rebuild |
| Scope | no Release 1.0 infrastructure/hardening or AI | dependency/source/diff audit |
