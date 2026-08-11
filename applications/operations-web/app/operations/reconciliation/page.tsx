import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  getCurrentReconciliationRun,
  getReconciliationRuns,
  getSettlementBatches,
  getTenant,
  type CoreReconciliationRun,
} from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { ReconciliationStartForm } from "./reconciliation-actions";

export const dynamic = "force-dynamic";

export default async function ReconciliationPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  if (!session.selectedTenantId) return <Unavailable message="Choose and verify a Tenant first." />;

  const supportActive = isSupportSessionActive(session);
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable message="This Tenant is unavailable." />;

  const [runs, batches] = await Promise.all([
    getReconciliationRuns(session.selectedTenantId, session.accessToken, { limit: 50 }, readOptions),
    getSettlementBatches(session.selectedTenantId, session.accessToken, readOptions),
  ]);
  if (runs.kind === "unauthenticated" || batches.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (runs.kind !== "ok") return <Unavailable message="Reconciliation runs are unavailable." />;

  const familyIds = [...new Set(runs.runs.map((run) => run.batchFamilyId))];
  const currentResults = await Promise.all(familyIds.map((familyId) =>
    getCurrentReconciliationRun(session.selectedTenantId!, familyId, session.accessToken, readOptions)));
  const currentRunIds = new Set(currentResults.filter((result) => result.kind === "ok").map((result) => result.current.runId));

  return <main><section className="shell">
    <a href="/operations">← Operations</a>
    <div className="eyebrow">Financial operations / Reconciliation</div>
    <h1>{tenantResult.tenant.name}</h1>
    <p>Runs preserve immutable provider and internal evidence. Review discrepancies before promoting or posting.</p>
    {supportActive && <div className="panel status">Support mode is active. This page is read-only.</div>}
    {!supportActive && batches.kind === "ok" && <ReconciliationStartForm batches={batches.page.items} csrfToken={session.csrfToken} />}
    <RunTable runs={runs.runs} currentRunIds={currentRunIds} />
  </section></main>;
}

function RunTable({ runs, currentRunIds }: { runs: CoreReconciliationRun[]; currentRunIds: Set<string> }) {
  return <section className="panel"><div className="eyebrow">Run history</div>{runs.length === 0 ? <p>No reconciliation runs have been created.</p> : <div className="table-wrap"><table><caption className="sr-only">Reconciliation run history</caption><thead><tr><th>Run</th><th>Batch</th><th>Status</th><th>Counts</th><th>Current</th><th>Open</th></tr></thead><tbody>{runs.map((run) => <tr key={run.runId}>
    <td><strong>#{run.runNumber}</strong><span className="table-secondary monospace">{run.runId}</span></td>
    <td><span className="monospace">{run.batchVersionId}</span><span className="table-secondary">cutoff {formatDate(run.sourceCutoff)}</span></td>
    <td><span className="status-badge">{run.status}</span></td>
    <td>{run.matchedCount} matched · {run.unmatchedCount} unmatched · {run.discrepancyCount} discrepancies</td>
    <td>{currentRunIds.has(run.runId) ? <span className="status-badge">CURRENT</span> : "—"}</td>
    <td><a className="button secondary" href={`/operations/reconciliation/${run.runId}`}>Inspect</a></td>
  </tr>)}</tbody></table></div>}</section>;
}

function formatDate(value: string) { return new Date(value).toLocaleString(); }
function Unavailable({ message }: { message: string }) { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">{message}</div></section></main>; }
