import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getCase, getTenant, type CoreCase } from "../../../../lib/core";
import { redis } from "../../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../../lib/session";
import { CaseActions } from "../case-actions";

export const dynamic = "force-dynamic";

export default async function CaseDetailPage({ params }: { params: Promise<{ caseId: string }> }) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  const tenantId = isSupportSessionActive(session) ? session.supportTenantId : session.selectedTenantId;
  if (!tenantId) return <Message text="Choose and verify a Tenant first." />;
  const { caseId } = await params;
  const readOptions = isSupportSessionActive(session) ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(tenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Message text="This Tenant is unavailable right now." />;
  const result = await getCase(tenantId, caseId, session.accessToken, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (result.kind !== "ok") return <Message text={result.kind === "unavailable" ? "This Case is outside your authorized scope." : "Core could not load this Case right now."} />;
  const supportActive = isSupportSessionActive(session);
  return <main><section className="shell">
    <Link href="/operations/cases">← Cases</Link>
    <div className="eyebrow">Risk operations / Case detail</div>
    <h1>{result.caseFile.caseId}</h1>
    <p>{tenantResult.tenant.name}. Closed Cases remain directly addressable for authorized reopen actions.</p>
    {supportActive && <div className="panel status">Support mode is active. This view is read-only.</div>}
    <CaseSummary caseFile={result.caseFile} />
    <CaseActions caseFile={result.caseFile} csrfToken={session.csrfToken} readOnly={supportActive} />
  </section></main>;
}

function CaseSummary({ caseFile }: { caseFile: CoreCase }) {
  return <section className="panel"><p>Status: <span className="status-badge">{caseFile.status}</span></p><p>Source: {caseFile.sourceCategory} · {caseFile.sourceId}</p><p>Severity: {caseFile.severity} · Due: {new Date(caseFile.dueAt).toLocaleString()}</p><p>Resolution: {caseFile.resolution ?? "Unresolved"}</p><h2>History</h2>{caseFile.history.length === 0 ? <p>No history recorded.</p> : <div className="data-list">{caseFile.history.map((entry) => <div key={entry.sequence}><strong>{entry.eventType} · {entry.toStatus ?? "evidence"}</strong><span>{entry.reason} · {new Date(entry.occurredAt).toLocaleString()}</span></div>)}</div>}</section>;
}

function Message({ text }: { text: string }) { return <main><section className="shell"><Link href="/operations/cases">← Cases</Link><div className="panel status error">{text}</div></section></main>; }
