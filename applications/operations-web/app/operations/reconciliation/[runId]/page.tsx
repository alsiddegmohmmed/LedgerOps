import { cookies } from "next/headers";
import Link from "next/link";
import { redirect } from "next/navigation";
import {
  getCurrentReconciliationRun,
  getReconciliationPostings,
  getReconciliationResults,
  getReconciliationRun,
  getTenant,
  getTenantConfiguration,
  type CoreReconciliationPosting,
  type CoreReconciliationResult,
  type CoreReconciliationRun,
} from "../../../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../../../lib/formatting";
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
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable message="This Tenant is unavailable." />;
  const configurationResult = await getTenantConfiguration(session.selectedTenantId, session.accessToken, readOptions);
  if (configurationResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const displayLocale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const displayTimezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;
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
    <Link href="/operations/reconciliation">← Reconciliation runs</Link>
    <div className="eyebrow">Reconciliation run #{run.runNumber}</div>
    <h1><span className="status-badge">{run.status}</span></h1>
    <RunSummary run={run} isCurrent={currentResult.kind === "ok" && currentResult.current.runId === run.runId} displayLocale={displayLocale} displayTimezone={displayTimezone} />
    <ReconciliationRunActions run={run} csrfToken={session.csrfToken} supportActive={supportActive} />
    <ResultTable results={resultsResult.kind === "ok" ? resultsResult.results : []} />
    <PostingTable postings={postingsResult.kind === "ok" ? postingsResult.postings : []} displayLocale={displayLocale} displayTimezone={displayTimezone} />
  </section></main>;
}

function RunSummary({ run, isCurrent, displayLocale, displayTimezone }: { run: CoreReconciliationRun; isCurrent: boolean; displayLocale: string; displayTimezone: string }) {
  return <section className="panel"><div className="eyebrow">Immutable run metadata</div><p>{isCurrent && <span className="status-badge">CURRENT</span>} {run.matchedCount} matched · {run.unmatchedCount} unmatched · {run.discrepancyCount} discrepancies</p><dl className="definition-list"><dt>Batch version</dt><dd className="monospace">{run.batchVersionId}</dd><dt>Batch family</dt><dd className="monospace">{run.batchFamilyId}</dd><dt>Rules</dt><dd>{run.rulesVersion}</dd><dt>Source cutoff</dt><dd>{formatOperationsDateTime(run.sourceCutoff, displayLocale, displayTimezone)}</dd><dt>Created</dt><dd>{formatOperationsDateTime(run.createdAt, displayLocale, displayTimezone)}</dd></dl>{run.failureReason && <p className="status error">{run.failureReason}</p>}</section>;
}

function ResultTable({ results }: { results: CoreReconciliationResult[] }) {
  return <section className="panel"><div className="eyebrow">Results and discrepancies</div>{results.length === 0 ? <p>No results are available.</p> : <div className="table-wrap"><table><caption className="sr-only">Reconciliation results</caption><thead><tr><th>Subject</th><th>Result</th><th>Category</th><th>Provider values</th><th>Internal values</th></tr></thead><tbody>{results.map((result) => <tr key={result.resultId}><td><span className="status-badge">{result.subjectType ?? "PROVIDER_ONLY"}</span><span className="table-secondary monospace">{result.subjectId ?? result.resultId}</span></td><td>{result.resultStatus}</td><td>{result.discrepancyCategory ?? "—"}</td><td><code>{result.providerValuesJson}</code></td><td><code>{result.internalValuesJson}</code></td></tr>)}</tbody></table></div>}</section>;
}

function PostingTable({ postings, displayLocale, displayTimezone }: { postings: CoreReconciliationPosting[]; displayLocale: string; displayTimezone: string }) {
  return <section className="panel"><div className="eyebrow">Settlement postings</div>{postings.length === 0 ? <p>No posting instructions have been prepared.</p> : <div className="table-wrap"><table><caption className="sr-only">Settlement posting instructions</caption><thead><tr><th>Subject</th><th>Amount</th><th>Status</th><th>Ledger transaction</th><th>Created</th></tr></thead><tbody>{postings.map((posting) => <tr key={posting.settlementPostingId}><td>{posting.subjectType}<span className="table-secondary monospace">{posting.subjectId}</span></td><td>{posting.amount} {posting.currency}</td><td><span className="status-badge">{posting.applicationStatus}</span></td><td className="monospace">{posting.ledgerTransactionId ?? "—"}</td><td>{formatOperationsDateTime(posting.createdAt, displayLocale, displayTimezone)}</td></tr>)}</tbody></table></div>}</section>;
}

function Unavailable({ message }: { message: string }) { return <main><section className="shell"><Link href="/operations/reconciliation">← Reconciliation runs</Link><div className="panel status error">{message}</div></section></main>; }
