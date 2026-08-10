import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getPaymentPage, getTenant, type CorePaymentPage } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";

export const dynamic = "force-dynamic";

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function PaymentsPage({ searchParams }: { searchParams: SearchParams }) {
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

  const values = await searchParams;
  const filters = {
    platformId: first(values.platformId),
    merchantReference: first(values.merchantReference),
    providerId: first(values.providerId),
    customerId: first(values.customerId),
    from: first(values.from),
    to: first(values.to),
    minAmount: first(values.minAmount),
    maxAmount: first(values.maxAmount),
    state: first(values.state),
    riskDecision: first(values.riskDecision),
    reconciliationStatus: first(values.reconciliationStatus),
    cursor: first(values.cursor),
  };
  const result = await getPaymentPage(tenantId, session.accessToken, filters, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Payment operations</div>
        <h1>Payments</h1>
        <p>{tenantResult.tenant.name}. Results are filtered by the Core authorization context.</p>
        {supportActive && <div className="panel status">Support mode is active. This view is read-only and audited.</div>}
        <PaymentSearchForm filters={filters} />
        {result.kind === "unavailable" && <div className="panel status error">You cannot read Payments for this Tenant.</div>}
        {result.kind === "error" && <div className="panel status error">Core could not load Payments right now.</div>}
        {result.kind === "ok" && <PaymentTable page={result.page} filters={filters} />}
      </section>
    </main>
  );
}

function PaymentSearchForm({ filters }: { filters: Record<string, string | undefined> }) {
  return (
    <form className="panel form-grid form-grid-wide" method="get">
      <div className="grid-2">
        <label>Platform/payment ID<input name="platformId" defaultValue={filters.platformId} /></label>
        <label>Merchant reference<input name="merchantReference" defaultValue={filters.merchantReference} /></label>
        <label>Provider ID<input name="providerId" defaultValue={filters.providerId} /></label>
        <label>Customer ID<input name="customerId" defaultValue={filters.customerId} /></label>
        <label>From (ISO instant)<input name="from" defaultValue={filters.from} placeholder="2026-01-01T00:00:00Z" /></label>
        <label>To (exclusive ISO instant)<input name="to" defaultValue={filters.to} placeholder="2026-02-01T00:00:00Z" /></label>
        <label>Minimum amount<input name="minAmount" inputMode="decimal" defaultValue={filters.minAmount} /></label>
        <label>Maximum amount<input name="maxAmount" inputMode="decimal" defaultValue={filters.maxAmount} /></label>
        <label>State<input name="state" defaultValue={filters.state} placeholder="COMPLETED" /></label>
        <label>Risk decision<input name="riskDecision" defaultValue={filters.riskDecision} placeholder="APPROVE" /></label>
        <label>Reconciliation status<input name="reconciliationStatus" defaultValue={filters.reconciliationStatus} placeholder="AWAITING_BATCH" /></label>
      </div>
      <div className="button-row"><button type="submit">Search Payments</button><a className="button secondary" href="/operations/payments">Clear</a></div>
    </form>
  );
}

function PaymentTable({ page, filters }: { page: CorePaymentPage; filters: Record<string, string | undefined> }) {
  const nextQuery = new URLSearchParams();
  for (const [key, value] of Object.entries(filters)) {
    if (key !== "cursor" && value) nextQuery.set(key, value);
  }
  return (
    <div className="panel">
      {page.items.length === 0 ? <p>No Payments matched the current filters.</p> : (
        <div className="table-wrap"><table>
          <caption className="sr-only">Payment search results</caption>
          <thead><tr><th>Payment</th><th>Amount</th><th>State</th><th>Risk</th><th>Reconciliation</th><th>Created</th></tr></thead>
          <tbody>{page.items.map((payment) => (
            <tr key={payment.paymentId}>
              <th scope="row"><a href={`/operations/payments/${encodeURIComponent(payment.paymentId)}`} className="monospace">{payment.paymentId}</a><span className="table-secondary">Merchant ref: {payment.merchantReference}</span></th>
              <td>{payment.amount} {payment.currency}</td>
              <td><span className="status-badge">{payment.state}</span></td>
              <td>{payment.riskDecision ?? "—"}</td>
              <td>{payment.reconciliationStatus ?? "—"}</td>
              <td>{formatDate(payment.createdAt)}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      {page.nextCursor && <a className="button" href={`/operations/payments?${nextQuery.toString()}&cursor=${encodeURIComponent(page.nextCursor)}`}>Next page</a>}
    </div>
  );
}

function first(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

function formatDate(value: string) {
  return new Date(value).toLocaleString();
}

function MissingTenant() {
  return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant before searching Payments.</div></section></main>;
}

function Unavailable() {
  return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>;
}
