import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getAuditPage, getTenant, getTenantConfiguration } from "../../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../../lib/formatting";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";

export const dynamic = "force-dynamic";
type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function AuditPage({ searchParams }: { searchParams: SearchParams }) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  const supportActive = isSupportSessionActive(session);
  const tenantId = supportActive ? session.supportTenantId : session.selectedTenantId;
  if (!tenantId) return <MissingTenant />;
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(tenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable />;
  const configurationResult = await getTenantConfiguration(tenantId, session.accessToken, readOptions);
  const displayLocale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const displayTimezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;
  const values = await searchParams;
  const filters = {
    actorIssuer: first(values.actorIssuer), actorSubject: first(values.actorSubject), action: first(values.action), entity: first(values.entity), entityId: first(values.entityId), from: first(values.from), to: first(values.to), result: first(values.result), correlationId: first(values.correlationId), cursor: first(values.cursor),
  };
  const result = await getAuditPage(tenantId, session.accessToken, filters, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const nextQuery = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) if (key !== "cursor" && value) nextQuery.set(key, value);
  return <main><section className="shell"><a href="/operations">← Operations</a><div className="eyebrow">Audit operations</div><h1>Audit search</h1><p>{tenantResult.tenant.name}. Audit is Tenant-wide and read-only; filters are enforced by Core.</p>{supportActive && <div className="panel status">Support mode is active. This view is read-only and audited.</div>}<form className="panel form-grid form-grid-wide" method="get"><div className="grid-2"><label>Actor issuer<input name="actorIssuer" defaultValue={filters.actorIssuer} /></label><label>Actor subject<input name="actorSubject" defaultValue={filters.actorSubject} /></label><label>Action<input name="action" defaultValue={filters.action} /></label><label>Entity<input name="entity" defaultValue={filters.entity} /></label><label>Entity ID<input name="entityId" defaultValue={filters.entityId} /></label><label>Result<input name="result" defaultValue={filters.result} /></label><label>From (ISO instant)<input name="from" defaultValue={filters.from} /></label><label>To (exclusive ISO instant)<input name="to" defaultValue={filters.to} /></label><label>Correlation ID<input name="correlationId" defaultValue={filters.correlationId} /></label></div><div className="button-row"><button type="submit">Search Audit</button><a className="button secondary" href="/operations/audit">Clear</a></div></form>{result.kind === "unavailable" && <div className="panel status error">You cannot read Audit for this Tenant.</div>}{result.kind === "error" && <div className="panel status error">Core could not load Audit right now.</div>}{result.kind === "ok" && <section className="panel">{result.page.items.length === 0 ? <p>No Audit records matched the current filters.</p> : <div className="table-wrap"><table><thead><tr><th>Occurred</th><th>Action</th><th>Entity</th><th>Actor</th><th>Result/details</th><th>Correlation</th></tr></thead><tbody>{result.page.items.map((item) => <tr key={item.auditId}><td>{formatOperationsDateTime(item.occurredAt, displayLocale, displayTimezone)}</td><td>{item.action}</td><td>{item.entity}<span className="table-secondary">{item.entityId}</span></td><td>{item.principalType}<span className="table-secondary">{item.actorSubject}</span></td><td>{item.reason}<span className="table-secondary">{item.details}</span></td><td className="monospace">{item.correlationId}</td></tr>)}</tbody></table></div>}{result.page.nextCursor && <a className="button" href={`/operations/audit?${nextQuery.toString()}&cursor=${encodeURIComponent(result.page.nextCursor)}`}>Next page</a>}</section>}</section></main>;
}

function first(value: string | string[] | undefined) { return Array.isArray(value) ? value[0] : value; }
function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
