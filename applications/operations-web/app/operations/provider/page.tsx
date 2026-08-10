import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  getProviderHealth,
  getProviderHealthHistory,
  getProviderScenarioAssignments,
  getTenant,
  type CoreProviderHealth,
  type CoreProviderScenarioAssignment,
} from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { ProviderScenarioControls } from "./provider-controls";

export const dynamic = "force-dynamic";

export default async function ProviderOperationsPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  if (!session.selectedTenantId) {
    return <MissingTenant />;
  }

  const supportActive = isSupportSessionActive(session);
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable />;

  const [healthResult, historyResult, scenarioResult] = await Promise.all([
    getProviderHealth(session.selectedTenantId, session.accessToken, readOptions),
    getProviderHealthHistory(session.selectedTenantId, session.accessToken, readOptions),
    getProviderScenarioAssignments(session.accessToken),
  ]);
  if (healthResult.kind === "unauthenticated" || historyResult.kind === "unauthenticated" || scenarioResult.kind === "unauthenticated") {
    redirect("/api/auth/login?reason=session");
  }

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Provider operations</div>
        <h1>Scenarios and health</h1>
        <p>{tenantResult.tenant.name}. Health is durable Provider evidence; scenario assignments are Platform Admin controls.</p>
        <HealthPanel result={healthResult.kind === "ok" ? healthResult.health : null} history={historyResult.kind === "ok" ? historyResult.history : []} unavailable={healthResult.kind === "unavailable" || historyResult.kind === "unavailable"} />
        <ScenarioPanel result={scenarioResult.kind === "ok" ? scenarioResult.assignments : []} unavailable={scenarioResult.kind === "unavailable" || scenarioResult.kind === "error"} />
        {!supportActive && scenarioResult.kind === "ok" && <ProviderScenarioControls csrfToken={session.csrfToken} tenantId={session.selectedTenantId} />}
      </section>
    </main>
  );
}

function HealthPanel({ result, history, unavailable }: { result: CoreProviderHealth | null; history: CoreProviderHealth[]; unavailable: boolean }) {
  return (
    <section className="panel">
      <div className="eyebrow">Durable health</div>
      {unavailable ? <p>You are not authorized to read Provider health for this Tenant.</p> : !result ? <p>Provider health has not been evaluated yet.</p> : <><h2><span className="status-badge">{result.state}</span> {result.providerId}</h2><p>{result.completedCalls} completed calls · {result.successfulCommunications} successful communications · p95 {result.p95LatencyMillis} ms</p><p>Circuit: {result.circuitState} · evaluated {formatDate(result.evaluatedAt)}</p></>}
      {history.length > 0 && <div className="table-wrap"><table><caption className="sr-only">Provider health history</caption><thead><tr><th scope="col">Evaluated</th><th scope="col">State</th><th scope="col">Calls</th><th scope="col">Timeouts</th><th scope="col">System errors</th><th scope="col">p95</th></tr></thead><tbody>{history.map((item) => <tr key={item.evaluationId}><td>{formatDate(item.evaluatedAt)}</td><td>{item.state}</td><td>{item.completedCalls}</td><td>{item.timeoutCount}</td><td>{item.systemErrorCount}</td><td>{item.p95LatencyMillis} ms</td></tr>)}</tbody></table></div>}
    </section>
  );
}

function ScenarioPanel({ result, unavailable }: { result: CoreProviderScenarioAssignment[]; unavailable: boolean }) {
  return (
    <section className="panel">
      <div className="eyebrow">Scenario assignments</div>
      {unavailable ? <p>Scenario assignments are visible only to Platform Admins.</p> : result.length === 0 ? <p>No Provider scenario assignments are recorded.</p> : <div className="table-wrap"><table><caption className="sr-only">Provider scenario assignments</caption><thead><tr><th scope="col">Scope</th><th scope="col">Tenant</th><th scope="col">Payment</th><th scope="col">Profile</th><th scope="col">Created</th></tr></thead><tbody>{result.map((assignment) => <tr key={assignment.assignmentId}><td>{assignment.scope}</td><td className="monospace">{assignment.tenantId ?? "—"}</td><td className="monospace">{assignment.paymentId ?? "—"}</td><td className="monospace">{assignment.profileId} v{assignment.profileVersion}</td><td>{formatDate(assignment.createdAt)}</td></tr>)}</tbody></table></div>}
    </section>
  );
}

function formatDate(value: string) { return new Date(value).toLocaleString(); }
function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
