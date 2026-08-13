import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import {
  getOperationalSummary,
  getTenant,
  getTenantConfiguration,
  type CoreOperationalSummary,
} from "../../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../../lib/formatting";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { LiveSummaryStatus } from "./live-summary-status";

export const dynamic = "force-dynamic";

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function OperationalSummaryPage({ searchParams }: { searchParams: SearchParams }) {
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
  const locale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const timezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;

  const values = await searchParams;
  const from = first(values.from) ?? defaultFrom();
  const to = first(values.to) ?? new Date().toISOString();
  const merchantIds = values.merchantId === undefined
    ? []
    : (Array.isArray(values.merchantId) ? values.merchantId : [values.merchantId]).filter(Boolean);
  const result = await getOperationalSummary(tenantId, session.accessToken, {
    from,
    to,
    merchantIds,
  }, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");

  return (
    <main>
      <section className="shell">
        <Link href="/operations">← Operations</Link>
        <div className="eyebrow">Reporting / Operational summary</div>
        <h1>{tenantResult.tenant.name}</h1>
        <p>Read-only Reporting projection. The selected period uses absolute UTC instants.</p>
        {supportActive && <div className="panel status">Support mode is active. This view is read-only and audited.</div>}
        <SummaryFilters from={from} to={to} merchantIds={merchantIds} />
        {result.kind === "unavailable" && <div className="panel status error">You cannot read this operational summary for the selected Tenant or Merchant scope.</div>}
        {result.kind === "error" && <div className="panel status error">Reporting could not load this snapshot ({result.code ?? result.status}).</div>}
        {result.kind === "ok" && <SummaryView summary={result.summary} locale={locale} timezone={timezone} tenantId={tenantId} merchantIds={merchantIds} />}
      </section>
    </main>
  );
}

function SummaryFilters({ from, to, merchantIds }: { from: string; to: string; merchantIds: string[] }) {
  const merchantFields = [...merchantIds, ""];
  return (
    <form className="panel form-grid" method="get">
      <label>From (inclusive ISO instant)<input name="from" defaultValue={from} required /></label>
      <label>To (exclusive ISO instant)<input name="to" defaultValue={to} required /></label>
      <fieldset>
        <legend>Merchant ID filters (optional, repeatable)</legend>
        {merchantFields.map((merchantId, index) => <input key={`${merchantId}-${index}`} name="merchantId" defaultValue={merchantId} placeholder="Merchant UUID" />)}
      </fieldset>
      <div className="button-row"><button type="submit">Load summary</button><Link className="button secondary" href="/operations/summary">Reset</Link></div>
    </form>
  );
}

function SummaryView({ summary, locale, timezone, tenantId, merchantIds }: { summary: CoreOperationalSummary; locale: string; timezone: string; tenantId: string; merchantIds: string[] }) {
  const { metrics } = summary;
  return (
    <>
      <section className="panel status">
        <strong>Projection snapshot</strong>
        <span className="table-secondary">As of {formatOperationsDateTime(summary.asOf, locale, timezone)} · generation {summary.projection.generation} · cursor {summary.projection.cursor}</span>
        <LiveSummaryStatus tenantId={tenantId} cursor={summary.projection.cursor} merchantIds={merchantIds} />
      </section>
      <section className="panel">
        <div className="eyebrow">Payment volume</div>
        <h2>{metrics.paymentVolume.paymentCount.toLocaleString(locale)} Payments</h2>
        <div className="data-list">{metrics.paymentVolume.amountByCurrency.map((item) => <span key={item.currency}>{formatAmount(item.amount, item.currency, locale)} <Link href={operationsRecordsHref(metrics.paymentVolume.source.href)}>View records</Link></span>)}</div>
      </section>
      <section className="panel grid-2">
        <RateCard title="Payment success" metric={metrics.paymentSuccessRate} locale={locale} />
        <RateCard title="Payment failure" metric={metrics.paymentFailureRate} locale={locale} />
      </section>
      <section className="panel grid-2">
        <CountCard title="Manual reviews" metric={metrics.manualReviewCount} />
        <CountCard title="Open discrepancies" metric={metrics.openDiscrepancyCount} />
        <CountCard title="Unresolved Cases" metric={metrics.unresolvedCaseCount} />
        <div><div className="eyebrow">Provider health</div><h2><span className="status-badge">{metrics.providerHealth.currentState}</span></h2><p>Worst in period: {metrics.providerHealth.worstState}</p><p>Evaluated {formatOperationsDateTime(metrics.providerHealth.mostRecentEvaluationAt, locale, timezone)}</p><Link href={operationsRecordsHref(metrics.providerHealth.source.href)}>View evaluations</Link></div>
      </section>
    </>
  );
}

function RateCard({ title, metric, locale }: { title: string; metric: CoreOperationalSummary["metrics"]["paymentSuccessRate"]; locale: string }) {
  const value = metric.rate === null ? "—" : `${(Number(metric.rate) * 100).toLocaleString(locale, { maximumFractionDigits: 2 })}%`;
  return <div><div className="eyebrow">{title}</div><h2>{value}</h2><p>{metric.numerator.toLocaleString(locale)} of {metric.denominator.toLocaleString(locale)} definitive outcomes</p><div className="button-row"><Link href={operationsRecordsHref(metric.numeratorSource.href)}>Numerator records</Link><Link href={operationsRecordsHref(metric.denominatorSource.href)}>Denominator records</Link></div></div>;
}

function CountCard({ title, metric }: { title: string; metric: { count: number; source: { href: string } } }) {
  return <div><div className="eyebrow">{title}</div><h2>{metric.count.toLocaleString()}</h2><Link href={operationsRecordsHref(metric.source.href)}>View matching records</Link></div>;
}

function operationsRecordsHref(coreHref: string) {
  const url = new URL(coreHref, "http://core.internal");
  return `/operations/summary/records?${url.searchParams.toString()}`;
}

function formatAmount(amount: number | string, currency: string, locale: string) {
  return `${Number(amount).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

function defaultFrom() {
  const from = new Date();
  from.setUTCDate(from.getUTCDate() - 7);
  return from.toISOString();
}

function first(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

function MissingTenant() { return <main><section className="shell"><Link href="/operations">← Operations</Link><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><Link href="/operations">← Operations</Link><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
