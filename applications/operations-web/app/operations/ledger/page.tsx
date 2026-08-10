import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getLedgerBalance, getLedgerStatement, getTenant } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";

export const dynamic = "force-dynamic";
type SearchParams = Promise<Record<string, string | string[] | undefined>>;

export default async function LedgerPage({ searchParams }: { searchParams: SearchParams }) {
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
  const accountId = first(values.accountId);
  const from = first(values.from);
  const to = first(values.to);
  const asOf = first(values.asOf);
  const transactionId = first(values.transactionId);
  const balance = accountId ? await getLedgerBalance(tenantId, accountId, session.accessToken, { asOf }, readOptions) : null;
  const statement = accountId && from && to ? await getLedgerStatement(tenantId, accountId, session.accessToken, { from, to, limit: 100 }, readOptions) : null;
  if (balance?.kind === "unauthenticated" || statement?.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  return <main><section className="shell"><a href="/operations">← Operations</a><div className="eyebrow">Ledger operations</div><h1>Ledger</h1><p>{tenantResult.tenant.name}. Ledger queries are account-scoped and read-only; the API does not define an account-list endpoint.</p>{supportActive && <div className="panel status">Support mode is active. This view is read-only and audited.</div>}<form className="panel form-grid" method="get"><label>Ledger account ID<input name="accountId" defaultValue={accountId} required /></label><label>As-of (optional ISO instant)<input name="asOf" defaultValue={asOf} placeholder="2026-01-01T00:00:00Z" /></label><label>Statement from (inclusive)<input name="from" defaultValue={from} placeholder="2026-01-01T00:00:00Z" /></label><label>Statement to (exclusive)<input name="to" defaultValue={to} placeholder="2026-02-01T00:00:00Z" /></label><button type="submit">Load Ledger evidence</button></form>{transactionId && <div className="panel status">Payment navigation requested transaction <span className="monospace">{transactionId}</span>. The account statement above is the authoritative navigation surface.</div>}{balance && <section className="panel"><div className="eyebrow">Balance</div>{balance.kind === "unavailable" ? <p className="error">This Ledger account is unavailable in the selected Tenant.</p> : balance.kind === "error" ? <p className="error">Core could not load the balance.</p> : <><h2>{balance.balance.totalDebits} debits / {balance.balance.totalCredits} credits</h2><p>{balance.balance.currency} · as of {balance.balance.asOfExclusive}</p></>}</section>}{statement && <section className="panel"><div className="eyebrow">Statement</div>{statement.kind === "unavailable" ? <p className="error">This Ledger account is unavailable in the selected Tenant.</p> : statement.kind === "error" ? <p className="error">Core could not load the statement.</p> : <><p>{statement.statement.totalEntries} entries · {statement.statement.fromInclusive} to {statement.statement.toExclusive}</p><div className="table-wrap"><table><thead><tr><th>Posted</th><th>Direction</th><th>Amount</th><th>Source</th><th>Transaction</th></tr></thead><tbody>{statement.statement.entries.map((entry) => <tr key={`${entry.transactionId}-${entry.entryIndex}`}><td>{entry.postedAt}</td><td>{entry.direction}</td><td>{entry.amount} {entry.currency}</td><td>{entry.sourceType} / {entry.sourceId}</td><td className="monospace">{entry.transactionId}</td></tr>)}</tbody></table></div></>}</section>}</section></main>;
}

function first(value: string | string[] | undefined) { return Array.isArray(value) ? value[0] : value; }
function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant is unavailable or Core could not verify it right now.</div></section></main>; }
