import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import {
  getCaseQueue,
  getOperationalSummary,
  getOperationalSummaryRecords,
  getPaymentPage,
  getRiskReviewQueue,
  getTenant,
  getTenantConfiguration,
  type CoreOperationalSummary,
  type CoreOperationalSummaryRecordPage,
  type CorePaymentPage,
} from "../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../lib/formatting";
import { redis } from "../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../lib/session";
import { LiveSummaryStatus } from "./summary/live-summary-status";
import { TenantSelector } from "./tenant-selector";

export const dynamic = "force-dynamic";

type SummaryResult = Awaited<ReturnType<typeof getOperationalSummary>>;
type PaymentResult = Awaited<ReturnType<typeof getPaymentPage>>;
type ProjectionPaymentResult = Awaited<ReturnType<typeof getOperationalSummaryRecords>>;
type RiskResult = Awaited<ReturnType<typeof getRiskReviewQueue>>;
type CaseResult = Awaited<ReturnType<typeof getCaseQueue>>;

export default async function OperationsPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  const supportActive = isSupportSessionActive(session);
  const tenantId = supportActive ? session.supportTenantId : session.selectedTenantId;
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = tenantId
    ? await getTenant(tenantId, session.accessToken, readOptions)
    : null;

  if (tenantResult?.kind === "unauthenticated") redirect("/api/auth/login?reason=session");

  if (!tenantResult || tenantResult.kind !== "ok") {
    return (
      <main>
        <section className="shell">
          <header className="page-header">
            <div>
              <div className="eyebrow">Tenant selection</div>
              <h1>Operations</h1>
              <p>Choose a Tenant to continue.</p>
            </div>
          </header>
          <TenantSelector csrfToken={session.csrfToken} selectedTenantId={session.selectedTenantId} />
          {tenantResult?.kind === "unavailable" && <div className="panel status error">This Tenant is unavailable to the signed-in user.</div>}
          {tenantResult?.kind === "error" && <div className="panel status error">Core could not verify this Tenant right now.</div>}
        </section>
      </main>
    );
  }

  const configurationResult = await getTenantConfiguration(tenantResult.tenant.id, session.accessToken, readOptions);
  if (configurationResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const locale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const timezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;

  const from = defaultFrom();
  const to = new Date().toISOString();
  const [summaryResult, paymentResult, paymentProjectionResult, riskResult, caseResult] = await Promise.all([
    getOperationalSummary(tenantResult.tenant.id, session.accessToken, { from, to }, readOptions),
    getPaymentPage(tenantResult.tenant.id, session.accessToken, { from, to, limit: 8 }, readOptions),
    getOperationalSummaryRecords(tenantResult.tenant.id, session.accessToken, {
      metric: "PAYMENT_VOLUME",
      from,
      to,
      limit: 8,
    }, readOptions),
    getRiskReviewQueue(tenantResult.tenant.id, session.accessToken, readOptions),
    getCaseQueue(tenantResult.tenant.id, session.accessToken, readOptions),
  ]);

  if ([summaryResult, paymentResult, paymentProjectionResult, riskResult, caseResult].some((result) => result.kind === "unauthenticated")) {
    redirect("/api/auth/login?reason=session");
  }

  return (
    <main>
      <section className="shell">
        <header className="page-header overview-header">
          <div>
            <div className="eyebrow">Operations</div>
            <h1>Operations</h1>
            <p>{tenantResult.tenant.name}</p>
          </div>
          <div className="page-actions">
            <Link className="button secondary" href="/operations/payments">Payments</Link>
            <Link className="button secondary" href="/operations/summary">Summary</Link>
          </div>
        </header>

        <section className="tenant-context" aria-label="Active Tenant context">
          <div className="tenant-identity">
            <span className="tenant-mark" aria-hidden="true">{tenantResult.tenant.name.slice(0, 1).toUpperCase()}</span>
            <div>
              <h2>{tenantResult.tenant.name}</h2>
              <p className="monospace">{tenantResult.tenant.id}</p>
            </div>
          </div>
          <div className="tenant-facts">
            <div><span className="fact-label">Status</span><strong>{tenantResult.tenant.status}</strong></div>
            <div><span className="fact-label">Currency</span><strong>{tenantResult.tenant.defaultCurrency}</strong></div>
            <div><span className="fact-label">Locale</span><strong>{tenantResult.tenant.defaultLocale}</strong></div>
          </div>
        </section>

        {supportActive && (
          <section className="panel status support-context">
            <div>
              <strong>Support mode is active.</strong>
              <p>This workspace is read-only and audited.</p>
            </div>
            <Link className="button secondary" href="/operations/support">Support console</Link>
          </section>
        )}

        {summaryResult.kind === "ok" ? (
          <SummaryMetrics summary={summaryResult.summary} locale={locale} timezone={timezone} tenantId={tenantResult.tenant.id} />
        ) : (
          <DataError result={summaryResult} label="Operational summary" />
        )}

        <div className="overview-grid">
          <NeedsAttention summary={summaryResult} riskResult={riskResult} caseResult={caseResult} />
          <ProviderHealth summary={summaryResult} locale={locale} timezone={timezone} />
        </div>

        <RecentPayments
          result={paymentResult}
          projectionResult={paymentProjectionResult}
          expectedCount={summaryResult.kind === "ok" ? summaryResult.summary.metrics.paymentVolume.paymentCount : null}
          projectionHref={summaryResult.kind === "ok" ? operationsRecordsHref(summaryResult.summary.metrics.paymentVolume.source.href) : "/operations/summary"}
          locale={locale}
          timezone={timezone}
        />
      </section>
    </main>
  );
}

function SummaryMetrics({ summary, locale, timezone, tenantId }: { summary: CoreOperationalSummary; locale: string; timezone: string; tenantId: string }) {
  const { metrics } = summary;
  return (
    <section aria-labelledby="summary-metrics-heading">
      <div className="section-heading">
        <div>
          <h2 id="summary-metrics-heading">Last 7 days</h2>
          <p>Reporting snapshot · {formatOperationsDateTime(summary.asOf, locale, timezone)}</p>
        </div>
        <LiveSummaryStatus tenantId={tenantId} cursor={summary.projection.cursor} merchantIds={[]} />
      </div>
      <div className="metric-strip">
        <MetricItem label="Payment volume" value={metrics.paymentVolume.paymentCount.toLocaleString(locale)} meta={formatAmounts(metrics.paymentVolume.amountByCurrency, locale)} href={operationsRecordsHref(metrics.paymentVolume.source.href)} />
        <MetricItem label="Success rate" value={formatRate(metrics.paymentSuccessRate.rate, locale)} meta={`${metrics.paymentSuccessRate.numerator.toLocaleString(locale)} / ${metrics.paymentSuccessRate.denominator.toLocaleString(locale)}`} href={operationsRecordsHref(metrics.paymentSuccessRate.numeratorSource.href)} />
        <MetricItem label="Failure rate" value={formatRate(metrics.paymentFailureRate.rate, locale)} meta={`${metrics.paymentFailureRate.numerator.toLocaleString(locale)} / ${metrics.paymentFailureRate.denominator.toLocaleString(locale)}`} href={operationsRecordsHref(metrics.paymentFailureRate.numeratorSource.href)} />
        <MetricItem label="Risk reviews created" value={metrics.manualReviewCount.count.toLocaleString(locale)} meta="In period" href={operationsRecordsHref(metrics.manualReviewCount.source.href)} />
        <MetricItem label="Open discrepancies" value={metrics.openDiscrepancyCount.count.toLocaleString(locale)} meta="In period" href={operationsRecordsHref(metrics.openDiscrepancyCount.source.href)} />
        <MetricItem label="Unresolved Cases" value={metrics.unresolvedCaseCount.count.toLocaleString(locale)} meta="Created in period" href={operationsRecordsHref(metrics.unresolvedCaseCount.source.href)} />
      </div>
    </section>
  );
}

function MetricItem({ label, value, meta, href }: { label: string; value: string; meta: string; href: string }) {
  return (
    <div className="metric-item">
      <span className="metric-label">{label}</span>
      <strong className="metric-value">{value}</strong>
      <span className="metric-meta">{meta}</span>
      <Link className="metric-link" href={href}>View records</Link>
    </div>
  );
}

function NeedsAttention({ summary, riskResult, caseResult }: { summary: SummaryResult; riskResult: RiskResult; caseResult: CaseResult }) {
  const rows: Array<{ label: string; detail: string; count: number; href: string }> = [];
  if (summary.kind === "ok" && summary.summary.metrics.paymentFailureRate.numerator > 0) {
    rows.push({
      label: "Failed payment outcomes",
      detail: "Definitive failures in the selected period",
      count: summary.summary.metrics.paymentFailureRate.numerator,
      href: operationsRecordsHref(summary.summary.metrics.paymentFailureRate.numeratorSource.href),
    });
  }
  if (riskResult.kind === "ok") {
    const activeReviews = riskResult.reviews.filter((review) => review.status !== "DECIDED");
    if (activeReviews.length > 0) rows.push({ label: "Risk reviews awaiting decision", detail: "Unassigned, assigned, or escalated", count: activeReviews.length, href: "/operations/risk-reviews" });
  }
  if (summary.kind === "ok" && summary.summary.metrics.openDiscrepancyCount.count > 0) {
    rows.push({ label: "Open discrepancies", detail: "Detected and not closed", count: summary.summary.metrics.openDiscrepancyCount.count, href: operationsRecordsHref(summary.summary.metrics.openDiscrepancyCount.source.href) });
  }
  if (caseResult.kind === "ok") {
    const openCases = caseResult.cases.filter((caseFile) => !["RESOLVED", "CLOSED"].includes(caseFile.status));
    if (openCases.length > 0) rows.push({ label: "Unresolved Cases", detail: "Open, investigating, awaiting information, or reopened", count: openCases.length, href: "/operations/cases" });
  }
  if (summary.kind === "ok" && ["DEGRADED", "UNAVAILABLE"].includes(summary.summary.metrics.providerHealth.currentState)) {
    rows.push({ label: "Provider health", detail: `Current state: ${summary.summary.metrics.providerHealth.currentState}`, count: 1, href: "/operations/provider" });
  }

  return (
    <section className="section-block" aria-labelledby="attention-heading">
      <div className="section-heading"><div><h2 id="attention-heading">Needs attention</h2><p>Current exceptions and work queues.</p></div></div>
      <div className="attention-list">
        {rows.length === 0 ? <p className="attention-empty">No current exceptions are available.</p> : rows.map((row) => (
          <div className="attention-row" key={`${row.label}-${row.href}`}>
            <div><strong>{row.label}</strong><small>{row.detail}</small></div>
            <div><span className="attention-count">{row.count.toLocaleString()}</span><Link className="attention-link" href={row.href}>Review</Link></div>
          </div>
        ))}
        {(riskResult.kind === "unavailable" || caseResult.kind === "unavailable") && <p className="attention-unavailable">Some queues are not available for this Tenant scope.</p>}
      </div>
    </section>
  );
}

function ProviderHealth({ summary, locale, timezone }: { summary: SummaryResult; locale: string; timezone: string }) {
  return (
    <section className="section-block" aria-labelledby="provider-heading">
      <div className="section-heading"><div><h2 id="provider-heading">Provider health</h2><p>Current Reporting projection.</p></div><Link className="table-action" href="/operations/provider">Open provider</Link></div>
      {summary.kind !== "ok" ? <DataError result={summary} label="Provider health" compact /> : (
        <div className="provider-panel">
          <div className="table-wrap">
            <table className="provider-table">
              <caption className="sr-only">Provider health summary</caption>
              <thead><tr><th scope="col">State</th><th scope="col">Worst in period</th><th scope="col">Evaluated</th></tr></thead>
              <tbody><tr>
                <td><span className={`provider-state provider-state-${summary.summary.metrics.providerHealth.currentState.toLowerCase()}`}>{summary.summary.metrics.providerHealth.currentState}</span></td>
                <td>{summary.summary.metrics.providerHealth.worstState}</td>
                <td>{formatOperationsDateTime(summary.summary.metrics.providerHealth.mostRecentEvaluationAt, locale, timezone)}</td>
              </tr></tbody>
            </table>
          </div>
          <p className="snapshot-note">Generation {summary.summary.projection.generation} · cursor {summary.summary.projection.cursor.toLocaleString()}</p>
        </div>
      )}
    </section>
  );
}

function RecentPayments({ result, projectionResult, expectedCount, projectionHref, locale, timezone }: { result: PaymentResult; projectionResult: ProjectionPaymentResult; expectedCount: number | null; projectionHref: string; locale: string; timezone: string }) {
  return (
    <section className="section-block" aria-labelledby="recent-payments-heading">
      <div className="section-heading"><div><h2 id="recent-payments-heading">Recent payments</h2><p>Latest records in the active Tenant scope.</p></div><Link className="table-action" href="/operations/payments">Open Payments</Link></div>
      {result.kind === "ok" && result.page.items.length > 0 ? (
        <div className="recent-panel">
          <div className="table-wrap">
            <table className="recent-table">
              <caption className="sr-only">Recent payments</caption>
              <thead><tr><th scope="col">Payment</th><th scope="col">Amount</th><th scope="col">State</th><th scope="col">Risk</th><th scope="col">Updated</th><th scope="col"><span className="sr-only">Action</span></th></tr></thead>
              <tbody>{result.page.items.map((payment) => <RecentPaymentRow key={payment.paymentId} payment={payment} locale={locale} timezone={timezone} />)}</tbody>
            </table>
          </div>
        </div>
      ) : projectionResult.kind === "ok" && projectionResult.page.items.length > 0 ? (
        <ProjectionPaymentRecords page={projectionResult.page} href={projectionHref} locale={locale} timezone={timezone} />
      ) : result.kind !== "ok" ? (
        <DataError result={result} label="Recent payments" />
      ) : expectedCount && expectedCount > 0 ? (
        <p className="attention-unavailable">The Reporting summary lists {expectedCount.toLocaleString(locale)} payments, but no matching source or projection records are available.</p>
      ) : (
        <p className="attention-empty">No payments in this period.</p>
      )}
    </section>
  );
}

function ProjectionPaymentRecords({ page, href, locale, timezone }: { page: CoreOperationalSummaryRecordPage; href: string; locale: string; timezone: string }) {
  return (
    <div className="recent-panel">
      <p className="projection-note">Showing the Reporting projection records. Full payment detail is available from Payments when the source record exists.</p>
      <div className="table-wrap">
        <table className="recent-table">
          <caption className="sr-only">Recent payment projection records</caption>
          <thead><tr><th scope="col">Payment</th><th scope="col">Merchant</th><th scope="col">Occurred</th><th scope="col"><span className="sr-only">Action</span></th></tr></thead>
          <tbody>{page.items.map((item) => (
            <tr key={`${item.sourceType}-${item.sourceId}`}>
              <th scope="row"><span className="monospace recent-payment-id">{item.sourceId}</span><span className="table-secondary">{item.sourceType}</span></th>
              <td className="monospace">{item.merchantId ?? "Tenant-wide"}</td>
              <td>{formatOperationsDateTime(item.occurredAt, locale, timezone)}</td>
              <td><Link className="table-action" href={href}>View records</Link></td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </div>
  );
}

function RecentPaymentRow({ payment, locale, timezone }: { payment: CorePaymentPage["items"][number]; locale: string; timezone: string }) {
  return (
    <tr>
      <th scope="row"><Link className="table-action recent-payment-id" href={`/operations/payments/${encodeURIComponent(payment.paymentId)}`}>{payment.paymentId}</Link><span className="table-secondary">{payment.merchantReference}</span></th>
      <td className="recent-amount">{formatAmount(payment.amount, payment.currency, locale)}</td>
      <td><span className="status-badge">{payment.state}</span></td>
      <td>{payment.riskDecision ?? "—"}</td>
      <td>{formatOperationsDateTime(payment.updatedAt, locale, timezone)}</td>
      <td><Link className="table-action" href={`/operations/payments/${encodeURIComponent(payment.paymentId)}`}>Open</Link></td>
    </tr>
  );
}

function DataError({ result, label, compact = false }: { result: { kind: string; code?: string; status?: number }; label: string; compact?: boolean }) {
  const message = result.kind === "unavailable"
    ? `${label} is not available for this Tenant scope.`
    : `${label} could not be loaded${result.code ? ` (${result.code})` : result.status ? ` (${result.status})` : ""}.`;
  return <div className={`panel status error${compact ? " compact-error" : ""}`}><p>{message}</p></div>;
}

function formatAmounts(items: CoreOperationalSummary["metrics"]["paymentVolume"]["amountByCurrency"], locale: string) {
  return items.length === 0 ? "No amount" : items.map((item) => formatAmount(item.amount, item.currency, locale)).join(" · ");
}

function formatAmount(amount: number | string, currency: string, locale: string) {
  return `${Number(amount).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

function formatRate(rate: number | string | null, locale: string) {
  return rate === null ? "—" : `${(Number(rate) * 100).toLocaleString(locale, { maximumFractionDigits: 2 })}%`;
}

function operationsRecordsHref(coreHref: string) {
  const url = new URL(coreHref, "http://core.internal");
  return `/operations/summary/records?${url.searchParams.toString()}`;
}

function defaultFrom() {
  const from = new Date();
  from.setUTCDate(from.getUTCDate() - 7);
  return from.toISOString();
}
