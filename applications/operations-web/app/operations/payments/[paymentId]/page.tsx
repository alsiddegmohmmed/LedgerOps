import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getPaymentDetail, getTenant, type CorePaymentDetail } from "../../../../lib/core";
import { redis } from "../../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../../lib/session";
import { PaymentNoteForm } from "../payment-note-form";

export const dynamic = "force-dynamic";

export default async function PaymentDetailPage({ params }: { params: Promise<{ paymentId: string }> }) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  const supportActive = isSupportSessionActive(session);
  const tenantId = supportActive ? session.supportTenantId : session.selectedTenantId;
  if (!tenantId) return <MissingTenant />;
  const { paymentId } = await params;
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(tenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable />;
  const result = await getPaymentDetail(tenantId, paymentId, session.accessToken, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");

  return (
    <main><section className="shell">
      <a href="/operations/payments">← Payments</a>
      {result.kind === "unavailable" && <div className="panel status error">This Payment is outside your authorized scope.</div>}
      {result.kind === "error" && <div className="panel status error">Core could not load this Payment right now.</div>}
      {result.kind === "ok" && <PaymentDetail detail={result.detail} tenantName={tenantResult.tenant.name} csrfToken={session.csrfToken} supportActive={supportActive} />}
    </section></main>
  );
}

function PaymentDetail({ detail, tenantName, csrfToken, supportActive }: { detail: CorePaymentDetail; tenantName: string; csrfToken: string; supportActive: boolean }) {
  const payment = detail.payment;
  return <>
    <div className="eyebrow">Payment operations / detail</div>
    <h1>{payment.paymentId}</h1>
    <p>{tenantName} · {payment.merchantId} · {payment.customerId}</p>
    {supportActive && <div className="panel status">Support mode is active. This view is read-only and audited.</div>}
    <div className="grid-2">
      <section className="panel"><div className="eyebrow">Payment</div><h2>{payment.amount} {payment.currency}</h2><p>Status: <span className="status-badge">{payment.state}</span></p><p>Method: {payment.paymentMethodCategory}</p><p>Created: {formatDate(payment.createdAt)}</p><p>Updated: {formatDate(payment.updatedAt)}</p><p>Reconciliation: {detail.reconciliationStatus}</p></section>
      <section className="panel"><div className="eyebrow">Risk</div>{detail.risk ? <><p>Decision: {detail.risk.decision}</p><p>Score: {detail.risk.finalScore}</p><p>Profile version: {detail.risk.profileVersion}</p><p>Evaluated: {formatDate(detail.risk.evaluatedAt)}</p></> : <p>No Risk evaluation evidence is available.</p>}</section>
    </div>
    <section className="panel"><div className="eyebrow">Attempts and Provider evidence</div>{detail.attempts.length === 0 ? <p>No Payment attempts.</p> : <div className="table-wrap"><table><thead><tr><th>Attempt</th><th>Provider</th><th>Idempotency key</th><th>Initiated</th></tr></thead><tbody>{detail.attempts.map((attempt) => <tr key={attempt.attemptId}><th scope="row">{attempt.sequence}</th><td>{attempt.providerId}</td><td className="monospace">{attempt.providerIdempotencyKey}</td><td>{formatDate(attempt.initiatedAt)}</td></tr>)}</tbody></table></div>}{detail.providerEvidence.length > 0 && <div className="data-list">{detail.providerEvidence.map((evidence) => <div key={evidence.evidenceId}><strong>{evidence.category}</strong><span>{evidence.providerId} · {evidence.providerReference ?? "no provider reference"} · {formatDate(evidence.observedAt)}</span></div>)}</div>}</section>
    <section className="panel"><div className="eyebrow">Ledger evidence</div>{detail.ledgerPosting ? <><p>Transaction: <span className="monospace">{detail.ledgerPosting.transactionId}</span></p><p>{detail.ledgerPosting.totalDebits} debits / {detail.ledgerPosting.totalCredits} credits · {detail.ledgerPosting.currency}</p><div className="data-list">{detail.ledgerPosting.entries.map((entry) => <div key={`${entry.accountId}-${entry.direction}`}><strong>{entry.direction} {entry.amount} {entry.currency} · {entry.accountCode}</strong><span className="monospace">Account {entry.accountId} · <a href={`/operations/ledger?accountId=${encodeURIComponent(entry.accountId)}&transactionId=${encodeURIComponent(detail.ledgerPosting!.transactionId)}`}>Open Ledger account</a></span></div>)}</div></> : <p>No Payment-source Ledger posting is present.</p>}</section>
    <section className="panel"><div className="eyebrow">Timeline</div>{detail.timeline.length === 0 ? <p>No timeline events are available.</p> : <div className="data-list">{detail.timeline.map((entry) => <div key={entry.sourceMessageId}><strong>{entry.displayText}</strong><span>{entry.outcome} · {entry.actorSource} · {formatDate(entry.occurredAt)}</span><span className="table-secondary">{entry.reasonCode} · {entry.sourceModule}/{entry.sourceType}</span></div>)}</div>}</section>
    <section className="panel"><div className="eyebrow">Notes</div>{detail.notes.length === 0 ? <p>No notes yet.</p> : <div className="data-list">{detail.notes.map((note) => <div key={note.noteId}><strong>{note.authorSubject}</strong><span>{formatDate(note.createdAt)}</span><p>{note.content}</p></div>)}</div>}<PaymentNoteForm paymentId={payment.paymentId} csrfToken={csrfToken} disabled={supportActive} /></section>
  </>;
}

function formatDate(value: string) { return new Date(value).toLocaleString(); }
function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
