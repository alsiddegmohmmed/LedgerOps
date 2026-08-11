import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import {
  getCurrentReconciliationRun,
  getReconciliationPostings,
  getReconciliationResults,
  getReconciliationRun,
  type CoreReconciliationPosting,
  type CoreReconciliationResult,
  type CoreReconciliationRun,
} from "../../../../lib/core";
import { redis } from "../../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../../lib/session";
import { ReconciliationRunActions } from "../reconciliation-actions";

export const dynamic = "force-dynamic";

export default async function ReconciliationRunPage({ params }: { params: Promise<{ runId: string }> }) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  if (!session.selectedTenantId) return <Unavailable message="Choose and verify a Tenant first." />;
  const { runId } = await params;
  const supportActive = isSupportSessionActive(session);
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const [runResult, resultsResult, postingsResult, currentResult] = await Promise.all([
    getReconciliationRun(session.selectedTenantId, runId, session.accessToken, readOptions),
    getReconciliationResults(session.selectedTenantId, runId, session.accessToken, { limit: 100 }, readOptions),
    getReconciliationPostings(session.selectedTenantId, runId, session.accessToken, { limit: 100 }, readOptions),
    getReconciliationRun(session.selectedTenantId, runId, session.accessToken, readOptions).then((result) =>
      result.kind === "ok" ? getCurrentReconciliationRun(session.selectedTenantId!, result.run.batchFamilyId, session.accessToken, readOptions) : result),
  ]);
  if (runResult.kind === "unauthenticated" || resultsResult.kind === "unauthenticated" || postingsResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (runResult.kind !== "ok") return <Unavailable message="The reconciliation run is unavailable." />;
  const run = runResult.run;

  return <main><section className="shell">
    <a href="/operations/reconciliation">← Reconciliation runs</a>
    <div className="eyebrow">Reconciliation run #{run.runNumber}</div>
    <h1><span className="status-badge">{run.status}</span></h1>
    <RunSummary run={run} isCurrent={currentResult.kind === "ok" && currentResult.current.runId === run.runId} />
    <ReconciliationRunActions run={run} csrfToken={session.csrfToken} supportActive={supportActive} />
    <ResultTable results={resultsResult.kind === "ok" ? resultsResult.results : []} />
    <PostingTable postings={postingsResult.kind === "ok" ? postingsResult.postings : []} />
  </section></main>;
}

function RunSummary({ run, isCurrent }: { run: CoreReconciliationRun; isCurrent: boolean }) {
  return <section className="panel"><div className="eyebrow">Immutable run metadata</div><p>{isCurrent && <span className="status-badge">CURRENT</span>} {run.matchedCount} matched · {run.unmatchedCount} unmatched · {run.discrepancyCount} discrepancies</p><dl className="definition-list"><dt>Batch version</dt><dd className="monospace">{run.batchVersionId}</dd><dt>Batch family</dt><dd className="monospace">{run.batchFamilyId}</dd><dt>Rules</dt><dd>{run.rulesVersion}</dd><dt>Source cutoff</dt><dd>{formatDate(run.sourceCutoff)}</dd><dt>Created</dt><dd>{formatDate(run.createdAt)}</dd></dl>{run.failureReason && <p className="status error">{run.failureReason}</p>}</section>;
}

function ResultTable({ results }: { results: CoreReconciliationResult[] }) {
  return <section className="panel"><div className="eyebrow">Results and discrepancies</div>{results.length === 0 ? <p>No results are available.</p> : <div className="table-wrap"><table><caption className="sr-only">Reconciliation results</caption><thead><tr><th>Subject</th><th>Result</th><th>Category</th><th>Provider values</th><th>Internal values</th></tr></thead><tbody>{results.map((result) => <tr key={result.resultId}><td><span className="status-badge">{result.subjectType ?? "PROVIDER_ONLY"}</span><span className="table-secondary monospace">{result.subjectId ?? result.resultId}</span></td><td>{result.resultStatus}</td><td>{result.discrepancyCategory ?? "—"}</td><td><code>{result.providerValuesJson}</code></td><td><code>{result.internalValuesJson}</code></td></tr>)}</tbody></table></div>}</section>;
}

function PostingTable({ postings }: { postings: CoreReconciliationPosting[] }) {
  return <section className="panel"><div className="eyebrow">Settlement postings</div>{postings.length === 0 ? <p>No posting instructions have been prepared.</p> : <div className="table-wrap"><table><caption className="sr-only">Settlement posting instructions</caption><thead><tr><th>Subject</th><th>Amount</th><th>Status</th><th>Ledger transaction</th><th>Created</th></tr></thead><tbody>{postings.map((posting) => <tr key={posting.settlementPostingId}><td>{posting.subjectType}<span className="table-secondary monospace">{posting.subjectId}</span></td><td>{posting.amount} {posting.currency}</td><td><span className="status-badge">{posting.applicationStatus}</span></td><td className="monospace">{posting.ledgerTransactionId ?? "—"}</td><td>{formatDate(posting.createdAt)}</td></tr>)}</tbody></table></div>}</section>;
}

function formatDate(value: string) { return new Date(value).toLocaleString(); }
function Unavailable({ message }: { message: string }) { return <main><section className="shell"><a href="/operations/reconciliation">← Reconciliation runs</a><div className="panel status error">{message}</div></section></main>; }
