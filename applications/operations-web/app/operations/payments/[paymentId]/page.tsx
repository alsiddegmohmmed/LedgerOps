import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import { getPaymentDetail, getTenant, getTenantConfiguration, type CorePaymentDetail } from "../../../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../../../lib/formatting";
import { redis } from "../../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../../lib/session";
import { PaymentNoteForm } from "../payment-note-form";
import { PaymentRetryForm } from "../payment-retry-form";
import { ReversalRequestForm } from "../reversal-request-form";
import { ReversalRetryForm } from "../reversal-retry-form";

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
  const configurationResult = await getTenantConfiguration(tenantId, session.accessToken, readOptions);
  if (configurationResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const displayLocale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const displayTimezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;
  const result = await getPaymentDetail(tenantId, paymentId, session.accessToken, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");

  return (
    <main><section className="shell">
      <Link href="/operations/payments">← Payments</Link>
      {result.kind === "unavailable" && <div className="panel status error">This Payment is outside your authorized scope.</div>}
      {result.kind === "error" && <div className="panel status error">Core could not load this Payment right now.</div>}
      {result.kind === "ok" && <PaymentDetail detail={result.detail} tenantName={tenantResult.tenant.name} csrfToken={session.csrfToken} supportActive={supportActive} displayLocale={displayLocale} displayTimezone={displayTimezone} />}
    </section></main>
  );
}

function PaymentDetail({ detail, tenantName, csrfToken, supportActive, displayLocale, displayTimezone }: { detail: CorePaymentDetail; tenantName: string; csrfToken: string; supportActive: boolean; displayLocale: string; displayTimezone: string }) {
  const payment = detail.payment;
  const reversalRecovery = detail.providerOperations?.recovery
    .filter((recovery) => recovery.operationType === "REVERSAL"
      && recovery.operationId === detail.reversal?.reversalId)
    .sort((left, right) => right.observedAt.localeCompare(left.observedAt)) ?? [];
  const latestReversalRecovery = reversalRecovery[0];
  const safeRetryEvidence = detail.reversal?.status === "FAILED"
    && latestReversalRecovery?.retryDisposition === "SAFE_TO_RESUBMIT"
    && latestReversalRecovery.providerTransactionFound === false
    && latestReversalRecovery.noAcceptanceProven === true
    ? latestReversalRecovery : null;
  return <>
    <div className="eyebrow">Payment operations / detail</div>
    <h1>{payment.paymentId}</h1>
    <p>{tenantName} · {payment.merchantId} · {payment.customerId}</p>
    {supportActive && <div className="panel status">Support mode is active. This view is read-only and audited.</div>}
    <div className="grid-2">
      <section className="panel"><div className="eyebrow">Payment</div><h2>{payment.amount} {payment.currency}</h2><p>Status: <span className="status-badge">{payment.state}</span></p><p>Method: {payment.paymentMethodCategory}</p><p>Created: {formatOperationsDateTime(payment.createdAt, displayLocale, displayTimezone)}</p><p>Updated: {formatOperationsDateTime(payment.updatedAt, displayLocale, displayTimezone)}</p><p>Reconciliation: {detail.reconciliationStatus}</p></section>
      <section className="panel"><div className="eyebrow">Risk</div>{detail.risk ? <><p>Decision: {detail.risk.decision}</p><p>Score: {detail.risk.finalScore}</p><p>Profile version: {detail.risk.profileVersion}</p><p>Evaluated: {formatOperationsDateTime(detail.risk.evaluatedAt, displayLocale, displayTimezone)}</p></> : <p>No Risk evaluation evidence is available.</p>}</section>
    </div>
    <section className="panel"><div className="eyebrow">Attempts and Provider evidence</div>{detail.attempts.length === 0 ? <p>No Payment attempts.</p> : <div className="table-wrap"><table><thead><tr><th>Attempt</th><th>Provider</th><th>Idempotency key</th><th>Initiated</th></tr></thead><tbody>{detail.attempts.map((attempt) => <tr key={attempt.attemptId}><th scope="row">{attempt.sequence}</th><td>{attempt.providerId}</td><td className="monospace">{attempt.providerIdempotencyKey}</td><td>{formatOperationsDateTime(attempt.initiatedAt, displayLocale, displayTimezone)}</td></tr>)}</tbody></table></div>}{detail.providerEvidence.length > 0 && <div className="data-list">{detail.providerEvidence.map((evidence) => <div key={evidence.evidenceId}><strong>{evidence.category}</strong><span>{evidence.providerId} · {evidence.providerReference ?? "no provider reference"} · {formatOperationsDateTime(evidence.observedAt, displayLocale, displayTimezone)}</span></div>)}</div>}</section>
    {detail.providerOperations && <section className="panel"><div className="eyebrow">Provider recovery and webhook evidence</div><p>{detail.providerOperations.work.length} durable work items · {detail.providerOperations.interactions.length} communication records · {detail.providerOperations.recovery.length} recovery records · {detail.providerOperations.webhooks.length} webhook events</p>{detail.providerOperations.work.length > 0 && <div className="data-list">{detail.providerOperations.work.map((work) => <div key={work.workId}><strong>{work.workType} · {work.status}</strong><span>Attempt {work.attemptSequence} · due {formatOperationsDateTime(work.dueAt, displayLocale, displayTimezone)} · executions {work.executionCount}</span><span className="table-secondary">{work.scenarioProfileId ? `Scenario ${work.scenarioProfileId} v${work.scenarioProfileVersion}` : "No pinned scenario"}{work.lastErrorCode ? ` · ${work.lastErrorCode}` : ""}</span></div>)}</div>}{detail.providerOperations.recovery.length > 0 && <div className="data-list">{detail.providerOperations.recovery.map((recovery) => <div key={recovery.evidenceId}><strong>{recovery.retryDisposition} · {recovery.resultCategory}</strong><span>{recovery.retryRequestId ? `Retry request ${recovery.retryRequestId}` : "No retry request"} · observed {formatOperationsDateTime(recovery.observedAt, displayLocale, displayTimezone)}</span><span className="table-secondary">{recovery.noAcceptanceProven ? "No acceptance proven" : "Acceptance not disproven"}</span></div>)}</div>}{detail.providerOperations.webhooks.length > 0 && <div className="data-list">{detail.providerOperations.webhooks.map((webhook) => <div key={webhook.eventId}><strong>Webhook · {webhook.status}</strong><span>{webhook.resultCategory} · {webhook.receiptCount} receipt(s) · {formatOperationsDateTime(webhook.receivedAt, displayLocale, displayTimezone)}</span></div>)}</div>}</section>}
    <section className="panel"><div className="eyebrow">Ledger evidence</div>{detail.ledgerPosting ? <><p>Transaction: <span className="monospace">{detail.ledgerPosting.transactionId}</span></p><p>{detail.ledgerPosting.totalDebits} debits / {detail.ledgerPosting.totalCredits} credits · {detail.ledgerPosting.currency}</p><div className="data-list">{detail.ledgerPosting.entries.map((entry) => <div key={`${entry.accountId}-${entry.direction}`}><strong>{entry.direction} {entry.amount} {entry.currency} · {entry.accountCode}</strong><span className="monospace">Account {entry.accountId} · <Link href={`/operations/ledger?accountId=${encodeURIComponent(entry.accountId)}&transactionId=${encodeURIComponent(detail.ledgerPosting!.transactionId)}`}>Open Ledger account</Link></span></div>)}</div></> : <p>No Payment-source Ledger posting is present.</p>}</section>
    <section className="panel"><div className="eyebrow">Timeline</div>{detail.timeline.length === 0 ? <p>No timeline events are available.</p> : <div className="data-list">{detail.timeline.map((entry) => <div key={entry.sourceMessageId}><strong>{entry.displayText}</strong><span>{entry.outcome} · {entry.actorSource} · {formatOperationsDateTime(entry.occurredAt, displayLocale, displayTimezone)}</span><span className="table-secondary">{entry.reasonCode} · {entry.sourceModule}/{entry.sourceType}</span></div>)}</div>}</section>
    <section className="panel"><div className="eyebrow">Manual retry</div><p>Retry now only accelerates an existing durable safe-to-resubmit work item. It does not create a Provider attempt.</p>{supportActive ? <p className="table-secondary">Support mode is read-only; retry acceleration is unavailable.</p> : <PaymentRetryForm paymentId={payment.paymentId} csrfToken={csrfToken} />}</section>
    {payment.state === "COMPLETED" && !detail.reversal && <section className="panel"><div className="eyebrow">Full Reversal</div><p>This action requests one full Reversal for the completed Payment. It does not post Ledger compensation or call the Provider from the request transaction.</p>{supportActive ? <p className="table-secondary">Support mode is read-only; Reversal requests are unavailable.</p> : <ReversalRequestForm paymentId={payment.paymentId} csrfToken={csrfToken} />}</section>}
    {detail.reversal && <section className="panel"><div className="eyebrow">Full Reversal</div><h2>{detail.reversal.amount} {detail.reversal.currency}</h2><p>Status: <span className="status-badge">{detail.reversal.status}</span></p><p>Reversal ID: <span className="monospace">{detail.reversal.reversalId}</span></p><p>Requested: {formatOperationsDateTime(detail.reversal.requestedAt, displayLocale, displayTimezone)}</p><p>Reason: {detail.reversal.requestReason}</p>{detail.reversal.processingAt && <p>Processing started: {formatOperationsDateTime(detail.reversal.processingAt, displayLocale, displayTimezone)}</p>}{detail.reversal.failedAt && <p>Failed: {formatOperationsDateTime(detail.reversal.failedAt, displayLocale, displayTimezone)}{detail.reversal.failureCategory ? ` · ${detail.reversal.failureCategory}` : ""}</p>}{detail.reversal.completedAt && <p>Completed: {formatOperationsDateTime(detail.reversal.completedAt, displayLocale, displayTimezone)}</p>}{detail.reversal.status === "FAILED" && (safeRetryEvidence ? (supportActive ? <p className="table-secondary">Support mode is read-only; safe Reversal retry is unavailable.</p> : <><p>The latest durable Provider evidence proves that resubmission is safe.</p><ReversalRetryForm reversalId={detail.reversal.reversalId} paymentId={payment.paymentId} previousAttemptId={safeRetryEvidence.attemptId} providerEvidenceId={safeRetryEvidence.evidenceId} csrfToken={csrfToken} /></>) : <p className="table-secondary">No current safe-to-resubmit evidence is available. Do not resubmit this Reversal blindly.</p>)}</section>}
    <section className="panel"><div className="eyebrow">Notes</div>{detail.notes.length === 0 ? <p>No notes yet.</p> : <div className="data-list">{detail.notes.map((note) => <div key={note.noteId}><strong>{note.authorSubject}</strong><span>{formatOperationsDateTime(note.createdAt, displayLocale, displayTimezone)}</span><p>{note.content}</p></div>)}</div>}<PaymentNoteForm paymentId={payment.paymentId} csrfToken={csrfToken} disabled={supportActive} /></section>
  </>;
}

function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
