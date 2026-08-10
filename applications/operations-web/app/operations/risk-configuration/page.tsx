import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getRiskConfiguration, getRiskConfigurationHistory, getTenant, type CoreRiskConfiguration } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../../lib/session";
import { RiskConfigurationForm } from "./risk-configuration-form";

export const dynamic = "force-dynamic";

export default async function RiskConfigurationPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  if (!session.selectedTenantId) return <MissingTenant />;
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable />;
  const [currentResult, historyResult] = await Promise.all([
    getRiskConfiguration(session.selectedTenantId, session.accessToken),
    getRiskConfigurationHistory(session.selectedTenantId, session.accessToken),
  ]);
  if (currentResult.kind === "unauthenticated" || historyResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  return <main><section className="shell"><a href="/operations">← Operations</a><div className="eyebrow">Administration / Risk configuration</div><h1>{tenantResult.tenant.name}</h1><p>Risk configuration is Tenant-wide. Merchant-scoped authority cannot change it.</p>{currentResult.kind === "unavailable" || currentResult.kind === "error" ? <div className="panel status error">You cannot read Risk configuration for this Tenant.</div> : currentResult.kind === "ok" ? <RiskConfigurationForm configuration={currentResult.configuration} csrfToken={session.csrfToken} /> : null}<History result={historyResult.kind === "ok" ? historyResult.history : []} /></section></main>;
}

function History({ result }: { result: CoreRiskConfiguration[] }) { return <section className="panel"><div className="eyebrow">Immutable history</div>{result.length === 0 ? <p>No Risk configuration history is available.</p> : <div className="table-wrap"><table><caption className="sr-only">Risk configuration history</caption><thead><tr><th scope="col">Version</th><th scope="col">Review</th><th scope="col">Reject</th><th scope="col">Rules</th><th scope="col">Created</th></tr></thead><tbody>{result.map((item) => <tr key={`${item.profileId}-${item.version}`}><td>{item.version}</td><td>{item.reviewThreshold}</td><td>{item.rejectThreshold}</td><td>{item.rules.length}</td><td>{new Date(item.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div>}</section>; }
function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
