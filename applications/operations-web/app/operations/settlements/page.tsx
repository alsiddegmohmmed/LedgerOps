import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  getSettlementBatches,
  getTenant,
  getTenantConfiguration,
  type CoreSettlementBatch,
} from "../../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../../lib/formatting";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { SettlementUploadForm } from "./settlement-actions";

export const dynamic = "force-dynamic";

export default async function SettlementPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  if (!session.selectedTenantId) return <MissingTenant />;

  const supportActive = isSupportSessionActive(session);
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable />;
  const configurationResult = await getTenantConfiguration(session.selectedTenantId, session.accessToken, readOptions);
  if (configurationResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const displayLocale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const displayTimezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;

  const batches = await getSettlementBatches(session.selectedTenantId, session.accessToken, readOptions);
  if (batches.kind === "unauthenticated") redirect("/api/auth/login?reason=session");

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Reconciliation / Settlement ingestion</div>
        <h1>{tenantResult.tenant.name}</h1>
        <p>Raw files are immutable. Validation imports only normalized records that pass the approved contract.</p>
        {supportActive && <div className="panel status">Settlement actions are unavailable in read-only support mode.</div>}
        {!supportActive && <SettlementUploadForm csrfToken={session.csrfToken} />}
        {batches.kind === "unavailable" && <div className="panel status error">You cannot read settlement batches for this Tenant.</div>}
        {batches.kind === "error" && <div className="panel status error">Core could not load settlement batches right now.</div>}
        {batches.kind === "ok" && <SettlementBatchTable batches={batches.page.items} displayLocale={displayLocale} displayTimezone={displayTimezone} />}
      </section>
    </main>
  );
}

function SettlementBatchTable({ batches, displayLocale, displayTimezone }: { batches: CoreSettlementBatch[]; displayLocale: string; displayTimezone: string }) {
  return (
    <section className="panel">
      <div className="eyebrow">Batch history</div>
      {batches.length === 0 ? <p>No settlement batches have been uploaded.</p> : (
        <div className="table-wrap">
          <table>
            <caption className="sr-only">Settlement batch history</caption>
            <thead><tr><th scope="col">Provider reference</th><th scope="col">Period</th><th scope="col">Status</th><th scope="col">Rows</th><th scope="col">Created</th><th scope="col">Open</th></tr></thead>
            <tbody>{batches.map((batch) => (
              <tr key={batch.batchVersionId}>
                <td><strong>{batch.providerBatchReference}</strong><span className="table-secondary monospace">{batch.rawFileSha256.slice(0, 16)}…</span></td>
                <td>{batch.settlementPeriodStart} → {batch.settlementPeriodEnd}</td>
                <td><span className="status-badge">{batch.status}</span>{batch.structuralErrorCode && <span className="table-secondary">{batch.structuralErrorCode}</span>}</td>
                <td>{batch.validRows}/{batch.totalRows} valid{batch.invalidRows > 0 ? ` · ${batch.invalidRows} quarantined` : ""}</td>
                <td>{formatOperationsDateTime(batch.createdAt, displayLocale, displayTimezone)}</td>
                <td><a className="button secondary" href={`/operations/settlements/${batch.batchVersionId}`}>Inspect</a></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
