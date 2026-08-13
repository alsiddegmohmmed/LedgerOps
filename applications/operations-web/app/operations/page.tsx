import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import {
  getOperationalSummary,
  getTenant,
  getTenantConfiguration,
  type CoreOperationalSummary,
} from "../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../lib/formatting";
import { redis } from "../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../lib/session";
import { LiveSummaryStatus } from "./summary/live-summary-status";
import { TenantSelector } from "./tenant-selector";

export const dynamic = "force-dynamic";

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

  if (tenantResult?.kind === "unauthenticated") {
    redirect("/api/auth/login?reason=session");
  }

  if (!tenantResult || tenantResult.kind !== "ok") {
    return (
      <main>
        <section className="shell dashboard-page">
          <header className="page-header">
            <div>
              <div className="eyebrow">Workspace setup</div>
              <h1>Choose a Tenant</h1>
              <p>Verify the Tenant you want to operate. Every view stays scoped to that selection.</p>
            </div>
          </header>
          <TenantSelector csrfToken={session.csrfToken} selectedTenantId={session.selectedTenantId} />
          {tenantResult?.kind === "unavailable" && (
            <div className="panel status error">This Tenant is unavailable to the signed-in user.</div>
          )}
          {tenantResult?.kind === "error" && (
            <div className="panel status error">Core could not verify this Tenant right now.</div>
          )}
        </section>
      </main>
    );
  }

  const from = defaultFrom();
  const to = new Date().toISOString();
  const summaryResult = await getOperationalSummary(tenantResult.tenant.id, session.accessToken, {
    from,
    to,
    merchantIds: [],
  }, readOptions);

  if (summaryResult.kind === "unauthenticated") {
    redirect("/api/auth/login?reason=session");
  }

  const configurationResult = await getTenantConfiguration(tenantResult.tenant.id, session.accessToken, readOptions);
  if (configurationResult.kind === "unauthenticated") {
    redirect("/api/auth/login?reason=session");
  }
  const displayLocale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const displayTimezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;

  return (
    <main>
      <section className="shell dashboard-page">
        <header className="page-header">
          <div>
            <div className="eyebrow">Workspace overview</div>
            <h1>Operations overview</h1>
            <p>One place to see what needs attention across payments, risk, reconciliation, and controls.</p>
          </div>
          <div className="page-actions">
            <Link className="button" href="/operations/payments">Open Payments</Link>
            <Link className="button secondary" href="/operations/summary">View summary</Link>
          </div>
        </header>

        <section className="tenant-hero">
          <div className="tenant-identity">
            <span className="tenant-mark" aria-hidden="true">{tenantResult.tenant.name.slice(0, 1).toUpperCase()}</span>
            <div>
              <span className="eyebrow">Active Tenant</span>
              <h2>{tenantResult.tenant.name}</h2>
              <p className="monospace">{tenantResult.tenant.id}</p>
            </div>
          </div>
          <div className="tenant-facts">
            <div><span className="fact-label">Status</span><span className="status-badge">{tenantResult.tenant.status}</span></div>
            <div><span className="fact-label">Currency</span><strong>{tenantResult.tenant.defaultCurrency}</strong></div>
            <div><span className="fact-label">Locale</span><strong>{tenantResult.tenant.defaultLocale}</strong></div>
          </div>
        </section>

        {supportActive && (
          <section className="panel status support-context">
            <div>
              <strong>Support session is active</strong>
              <p>This workspace is read-only and audited.</p>
            </div>
            <Link className="button secondary" href="/operations/support">Open support console</Link>
          </section>
        )}

        {summaryResult.kind === "ok" ? (
          <DashboardSummary
            summary={summaryResult.summary}
            locale={displayLocale}
            timezone={displayTimezone}
            tenantId={tenantResult.tenant.id}
            supportActive={supportActive}
          />
        ) : (
          <section className="panel status error">
            <strong>Overview unavailable</strong>
            <p>{summaryResult.kind === "unavailable"
              ? "You cannot read the operational summary for this Tenant."
              : `Reporting could not load the latest snapshot (${summaryResult.code ?? summaryResult.status}).`}</p>
            <Link className="button secondary" href="/operations/summary">Open operational summary</Link>
          </section>
        )}

        <section className="dashboard-grid">
          <div className="panel">
            <div className="panel-heading">
              <div>
                <div className="eyebrow">Work queues</div>
                <h2>Operator shortcuts</h2>
              </div>
              <span className="panel-kicker">Open a queue</span>
            </div>
            <div className="shortcut-grid">
              <Shortcut href="/operations/payments" label="Payments" description="Search and inspect payment evidence." glyph="↗" />
              <Shortcut href="/operations/risk-reviews" label="Risk reviews" description="Review items waiting for a decision." glyph="!" />
              <Shortcut href="/operations/cases" label="Cases" description="Investigate unresolved operational cases." glyph="◇" />
              <Shortcut href="/operations/reconciliation" label="Reconciliation" description="Inspect runs and discrepancies." glyph="≋" />
              <Shortcut href="/operations/audit" label="Audit trail" description="Search immutable Tenant evidence." glyph="◫" />
            </div>
          </div>

          <div className="panel posture-panel">
            <div className="panel-heading">
              <div>
                <div className="eyebrow">System posture</div>
                <h2>Reporting snapshot</h2>
              </div>
              <span className="status-dot status-dot-live" aria-label="Connected" />
            </div>
            {summaryResult.kind === "ok" ? (
              <>
                <div className="posture-value">Generation {summaryResult.summary.projection.generation}</div>
                <p>Snapshot composed {formatOperationsDateTime(summaryResult.summary.asOf, displayLocale, displayTimezone)}.</p>
                <div className="mini-stat"><span>Projection cursor</span><strong>{summaryResult.summary.projection.cursor.toLocaleString()}</strong></div>
                <div className="mini-stat"><span>Live updates</span><strong>Connected when opened</strong></div>
              </>
            ) : (
              <p>The Reporting projection is unavailable. Open the summary page for the current error details.</p>
            )}
            <Link className="text-link" href="/operations/provider">Check provider health <span aria-hidden="true">→</span></Link>
          </div>
        </section>
      </section>
    </main>
  );
}

function DashboardSummary({
  summary,
  locale,
  timezone,
  tenantId,
  supportActive,
}: {
  summary: CoreOperationalSummary;
  locale: string;
  timezone: string;
  tenantId: string;
  supportActive: boolean;
}) {
  const { metrics } = summary;
  return (
    <section className="dashboard-summary">
      <div className="section-heading">
        <div>
          <div className="eyebrow">Last 7 days</div>
          <h2>Operational pulse</h2>
        </div>
        <div className="snapshot-meta">
          <span>As of {formatOperationsDateTime(summary.asOf, locale, timezone)}</span>
          <LiveSummaryStatus tenantId={tenantId} cursor={summary.projection.cursor} merchantIds={[]} />
        </div>
      </div>
      <div className="metric-grid">
        <MetricCard
          label="Payment volume"
          value={metrics.paymentVolume.paymentCount.toLocaleString(locale)}
          meta={metrics.paymentVolume.amountByCurrency.map((item) => formatAmount(item.amount, item.currency, locale)).join(" · ") || "No payments in period"}
          href={operationsRecordsHref(metrics.paymentVolume.source.href)}
          tone="accent"
        />
        <MetricCard
          label="Success rate"
          value={formatRate(metrics.paymentSuccessRate.rate, locale)}
          meta={`${metrics.paymentSuccessRate.numerator.toLocaleString(locale)} of ${metrics.paymentSuccessRate.denominator.toLocaleString(locale)} definitive outcomes`}
          href={operationsRecordsHref(metrics.paymentSuccessRate.numeratorSource.href)}
          tone="positive"
        />
        <MetricCard
          label="Manual reviews"
          value={metrics.manualReviewCount.count.toLocaleString(locale)}
          meta="Created in selected period"
          href={operationsRecordsHref(metrics.manualReviewCount.source.href)}
          tone="amber"
        />
        <MetricCard
          label="Open discrepancies"
          value={metrics.openDiscrepancyCount.count.toLocaleString(locale)}
          meta="Detected and not closed"
          href={operationsRecordsHref(metrics.openDiscrepancyCount.source.href)}
          tone="warning"
        />
        <MetricCard
          label="Unresolved Cases"
          value={metrics.unresolvedCaseCount.count.toLocaleString(locale)}
          meta="Open, investigating, or reopened"
          href={operationsRecordsHref(metrics.unresolvedCaseCount.source.href)}
          tone="warning"
        />
        <MetricCard
          label="Provider health"
          value={metrics.providerHealth.currentState}
          meta={`Worst in period: ${metrics.providerHealth.worstState}`}
          href={operationsRecordsHref(metrics.providerHealth.source.href)}
          tone="neutral"
          compact
        />
      </div>
      <div className="summary-footnote">
        <span>Projection generation {summary.projection.generation} · cursor {summary.projection.cursor.toLocaleString()}</span>
        {!supportActive && <Link href="/operations/summary">Open the full summary and filters <span aria-hidden="true">→</span></Link>}
      </div>
    </section>
  );
}

function MetricCard({
  label,
  value,
  meta,
  href,
  tone,
  compact = false,
}: {
  label: string;
  value: string;
  meta: string;
  href: string;
  tone: "accent" | "positive" | "amber" | "warning" | "neutral";
  compact?: boolean;
}) {
  return (
    <article className={`metric-card metric-card-${tone}`}>
      <div className="metric-label"><span className="metric-indicator" />{label}</div>
      <div className={`metric-value${compact ? " metric-value-compact" : ""}`}>{value}</div>
      <p className="metric-meta">{meta}</p>
      <Link className="metric-link" href={href}>View records <span aria-hidden="true">→</span></Link>
    </article>
  );
}

function Shortcut({ href, label, description, glyph }: { href: string; label: string; description: string; glyph: string }) {
  return (
    <Link className="shortcut-card" href={href}>
      <span className="shortcut-glyph" aria-hidden="true">{glyph}</span>
      <span><strong>{label}</strong><small>{description}</small></span>
      <span className="shortcut-arrow" aria-hidden="true">↗</span>
    </Link>
  );
}

function operationsRecordsHref(coreHref: string) {
  const url = new URL(coreHref, "http://core.internal");
  return `/operations/summary/records?${url.searchParams.toString()}`;
}

function formatAmount(amount: number | string, currency: string, locale: string) {
  return `${Number(amount).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

function formatRate(rate: number | string | null, locale: string) {
  return rate === null ? "—" : `${(Number(rate) * 100).toLocaleString(locale, { maximumFractionDigits: 2 })}%`;
}

function defaultFrom() {
  const from = new Date();
  from.setUTCDate(from.getUTCDate() - 7);
  return from.toISOString();
}
