import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getCaseQueue, getTenant, type CoreCase } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { CaseActions } from "./case-actions";

export const dynamic = "force-dynamic";

export default async function CasesPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  const supportActive = isSupportSessionActive(session);
  const tenantId = supportActive ? session.supportTenantId : session.selectedTenantId;
  if (!tenantId) return <Message title="Casework" text="Choose and verify a Tenant first." />;

  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(tenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Message title="Casework" text="This Tenant is unavailable right now." />;

  const result = await getCaseQueue(tenantId, session.accessToken, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (result.kind !== "ok") return <Message title="Casework" text="Cases could not be loaded right now." />;

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Risk operations / Casework</div>
        <h1>Cases</h1>
        <p>{tenantResult.tenant.name}. Investigate, preserve history, resolve, and close authorized cases.</p>
        {supportActive && <div className="panel status">Support mode is active. This queue is read-only.</div>}
        <CaseTable cases={result.cases} csrfToken={session.csrfToken} readOnly={supportActive} />
      </section>
    </main>
  );
}

function CaseTable({ cases, csrfToken, readOnly }: { cases: CoreCase[]; csrfToken: string; readOnly: boolean }) {
  return (
    <div className="panel">
      {cases.length === 0 ? <p>No cases are currently visible.</p> : (
        <div className="table-wrap"><table>
          <caption className="sr-only">Casework queue</caption>
          <thead><tr>
            <th scope="col">Case</th><th scope="col">Status</th><th scope="col">Severity</th>
            <th scope="col">Owner</th><th scope="col">Due</th><th scope="col">Source</th><th scope="col">Actions</th>
          </tr></thead>
          <tbody>{cases.map((caseFile) => (
            <tr key={caseFile.caseId}>
              <th scope="row" className="monospace">{caseFile.caseId}<span className="table-secondary">Created: {formatDate(caseFile.createdAt)}</span></th>
              <td><span className="status-badge">{caseFile.status}</span><span className="table-secondary">{caseFile.resolution ?? "Unresolved"}</span></td>
              <td>{caseFile.severity}</td>
              <td>{caseFile.ownerId ?? "Unassigned"}</td>
              <td>{formatDate(caseFile.dueAt)}<span className="table-secondary">{overdueText(caseFile.dueAt)}</span></td>
              <td>{caseFile.sourceCategory}<span className="table-secondary monospace">{caseFile.sourceId}</span></td>
              <td><CaseActions caseFile={caseFile} csrfToken={csrfToken} readOnly={readOnly} /></td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </div>
  );
}

function formatDate(value: string) { return new Date(value).toLocaleString(); }
function overdueText(value: string) { return new Date(value).getTime() <= Date.now() ? "Overdue" : "Within target"; }
function Message({ title, text }: { title: string; text: string }) {
  return <main><section className="shell"><a href="/operations">← Operations</a><h1>{title}</h1><div className="panel status error">{text}</div></section></main>;
}
