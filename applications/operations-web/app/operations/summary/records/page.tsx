import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import {
  getOperationalSummaryRecords,
  getTenant,
  getTenantConfiguration,
  type CoreOperationalSummaryMetricCode,
} from "../../../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../../../lib/formatting";
import { redis } from "../../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../../lib/session";

export const dynamic = "force-dynamic";

type SearchParams = Promise<Record<string, string | string[] | undefined>>;
const METRICS: CoreOperationalSummaryMetricCode[] = [
  "PAYMENT_VOLUME", "PAYMENT_SUCCESS", "PAYMENT_FAILURE", "PAYMENT_PROVIDER_TERMINAL",
  "MANUAL_REVIEW", "OPEN_DISCREPANCY", "UNRESOLVED_CASE", "PROVIDER_HEALTH_EVALUATION",
];

export default async function OperationalSummaryRecordsPage({ searchParams }: { searchParams: SearchParams }) {
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
  if (configurationResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const locale = configurationResult.kind === "ok" ? configurationResult.configuration.defaultLocale : tenantResult.tenant.defaultLocale;
  const timezone = configurationResult.kind === "ok" ? configurationResult.configuration.timezone : DEFAULT_OPERATIONS_TIMEZONE;

  const values = await searchParams;
  const metric = parseMetric(first(values.metric));
  const from = first(values.from);
  const to = first(values.to);
  const after = first(values.after);
  const merchantIds = (Array.isArray(values.merchantId) ? values.merchantId : [values.merchantId]).filter((value): value is string => Boolean(value));
  const limit = parseLimit(first(values.limit));
  if (!metric || !from || !to || limit === null) return <InvalidQuery />;

  const result = await getOperationalSummaryRecords(tenantId, session.accessToken, {
    metric, from, to, merchantIds, after, limit,
  }, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const nextQuery = new URLSearchParams({ metric, from, to, limit: String(limit) });
  for (const merchantId of merchantIds) nextQuery.append("merchantId", merchantId);

  return (
    <main>
      <section className="shell">
        <Link href={`/operations/summary?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`}>← Operational summary</Link>
        <div className="eyebrow">Reporting / Source records</div>
        <h1>{metricLabel(metric)}</h1>
        <p>{tenantResult.tenant.name}. These records are the projection-backed drill-down for the selected metric.</p>
        {supportActive && <div className="panel status">Support mode is active. This view is read-only and audited.</div>}
        {result.kind === "unavailable" && <div className="panel status error">You cannot read these records for the selected Tenant or Merchant scope.</div>}
        {result.kind === "error" && <div className="panel status error">Reporting could not load these records ({result.code ?? result.status}).</div>}
        {result.kind === "ok" && <RecordTable page={result.page} locale={locale} timezone={timezone} nextQuery={nextQuery} />}
      </section>
    </main>
  );
}

function RecordTable({ page, locale, timezone, nextQuery }: { page: { items: { sourceType: string; sourceId: string; merchantId: string | null; occurredAt: string; sourceDetailHref: string | null }[]; nextAfter: string | null }; locale: string; timezone: string; nextQuery: URLSearchParams }) {
  return <section className="panel">{page.items.length === 0 ? <p>No matching records were found.</p> : <div className="table-wrap"><table><caption className="sr-only">Operational summary source records</caption><thead><tr><th scope="col">Occurred</th><th scope="col">Source</th><th scope="col">Source ID</th><th scope="col">Merchant</th></tr></thead><tbody>{page.items.map((item) => <tr key={`${item.sourceType}-${item.sourceId}`}><td>{formatOperationsDateTime(item.occurredAt, locale, timezone)}</td><td>{item.sourceType}</td><td className="monospace">{item.sourceId}</td><td className="monospace">{item.merchantId ?? "Tenant-wide"}</td></tr>)}</tbody></table></div>}{page.nextAfter && <Link className="button" href={`/operations/summary/records?${nextQuery.toString()}&after=${encodeURIComponent(page.nextAfter)}`}>Next page</Link>}</section>;
}

function parseMetric(value: string | undefined): CoreOperationalSummaryMetricCode | null {
  return value && METRICS.includes(value as CoreOperationalSummaryMetricCode) ? value as CoreOperationalSummaryMetricCode : null;
}

function parseLimit(value: string | undefined) {
  if (!value) return 25;
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= 100 ? parsed : null;
}

function metricLabel(metric: CoreOperationalSummaryMetricCode) {
  return metric.replaceAll("_", " ");
}

function first(value: string | string[] | undefined) { return Array.isArray(value) ? value[0] : value; }
function MissingTenant() { return <main><section className="shell"><Link href="/operations">← Operations</Link><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><Link href="/operations">← Operations</Link><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
function InvalidQuery() { return <main><section className="shell"><Link href="/operations/summary">← Operational summary</Link><div className="panel status error">The source-record query is missing a valid metric, period, or limit.</div></section></main>; }
