import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  getSettlementBatch,
  getSettlementValidationItems,
  getTenant,
  type CoreSettlementBatch,
  type CoreSettlementValidationItem,
} from "../../../../lib/core";
import { redis } from "../../../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../../../lib/session";
import { SettlementBatchActions } from "../settlement-actions";

export const dynamic = "force-dynamic";

export default async function SettlementBatchDetailPage({ params }: { params: Promise<{ batchVersionId: string }> }) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  if (!session.selectedTenantId) return <Unavailable message="Choose and verify a Tenant first." />;

  const { batchVersionId } = await params;
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable message="This Tenant is unavailable." />;

  const [batchResult, itemsResult] = await Promise.all([
    getSettlementBatch(session.selectedTenantId, batchVersionId, session.accessToken),
    getSettlementValidationItems(session.selectedTenantId, batchVersionId, session.accessToken),
  ]);
  if (batchResult.kind === "unauthenticated" || itemsResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (batchResult.kind !== "ok") return <Unavailable message="The settlement batch is unavailable." />;

  return <main><section className="shell"><a href="/operations/settlements">← Settlement ingestion</a><div className="eyebrow">Settlement batch</div><h1>{batchResult.batch.providerBatchReference}</h1><p>{batchResult.batch.settlementPeriodStart} → {batchResult.batch.settlementPeriodEnd} · {batchResult.batch.rawFileSha256}</p><BatchSummary batch={batchResult.batch} />
    <SettlementBatchActions batch={batchResult.batch} csrfToken={session.csrfToken} />
    {itemsResult.kind === "ok" ? <ValidationItems items={itemsResult.items} /> : <div className="panel status error">Validation details are unavailable.</div>}
  </section></main>;
}

function BatchSummary({ batch }: { batch: CoreSettlementBatch }) {
  return <section className="panel"><div className="eyebrow">State</div><h2><span className="status-badge">{batch.status}</span></h2><p>{batch.validRows} valid rows · {batch.invalidRows} quarantined rows · {batch.totalRows} total rows</p>{batch.structuralErrorCode && <p className="status error">Structural result: {batch.structuralErrorCode}</p>}<p className="table-secondary">Object bytes: {batch.byteSize.toLocaleString()} · created {new Date(batch.createdAt).toLocaleString()}</p></section>;
}

function ValidationItems({ items }: { items: CoreSettlementValidationItem[] }) {
  return <section className="panel"><div className="eyebrow">Validation evidence</div>{items.length === 0 ? <p>No quarantined rows or structural validation items.</p> : <div className="table-wrap"><table><caption className="sr-only">Settlement validation items</caption><thead><tr><th scope="col">Row</th><th scope="col">Reason</th><th scope="col">Evidence</th><th scope="col">Created</th></tr></thead><tbody>{items.map((item) => <tr key={item.validationItemId}><td>{item.rowNumber}</td><td><span className="status-badge">{item.reasonCode}</span></td><td><code>{JSON.stringify(item.safeEvidence)}</code></td><td>{new Date(item.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div>}</section>;
}

function Unavailable({ message }: { message: string }) { return <main><section className="shell"><a href="/operations/settlements">← Settlement ingestion</a><div className="panel status error">{message}</div></section></main>; }
