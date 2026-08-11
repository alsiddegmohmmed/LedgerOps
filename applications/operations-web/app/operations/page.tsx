import { redirect } from "next/navigation";
import Link from "next/link";
import { getTenant } from "../../lib/core";
import { redis } from "../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../lib/session";
import { cookies } from "next/headers";
import { TenantSelector } from "./tenant-selector";

export const dynamic = "force-dynamic";

export default async function OperationsPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  const supportActive = isSupportSessionActive(session);
  const tenantId = supportActive ? session.supportTenantId : session.selectedTenantId;
  const tenantResult = tenantId
    ? await getTenant(tenantId, session.accessToken,
      supportActive ? { supportSessionId: session.supportSessionId } : {})
    : null;

  if (tenantResult?.kind === "unauthenticated") {
    redirect("/api/auth/login?reason=session");
  }

  return (
    <main>
      <section className="shell">
        <div className="eyebrow">Authenticated shell</div>
        <h1>Operations Web</h1>
        <p>Session state is server-side. Core remains the authorization source of truth.</p>
        <TenantSelector
          csrfToken={session.csrfToken}
          selectedTenantId={session.selectedTenantId}
        />
        {supportActive && (
          <div className="panel status">
            Support mode is active. Use the dedicated support console for audited read-only access.
            <br /><Link className="button secondary" href="/operations/support">Open support console</Link>
          </div>
        )}
        {!supportActive && tenantResult?.kind === "ok" && (
          <div className="panel status">
            <div className="eyebrow">Core-verified Tenant</div>
            <h2>{tenantResult.tenant.name}</h2>
            <p>{tenantResult.tenant.status} · {tenantResult.tenant.defaultCurrency} · {tenantResult.tenant.defaultLocale}</p>
          </div>
        )}
        {tenantResult?.kind === "ok" && (
          <div className="panel">
            <div className="eyebrow">Financial operations</div>
            <h2>Payments</h2>
            <p>Search scoped Payment records and inspect their composed operational evidence.</p>
            <Link className="button" href="/operations/payments">Open Payments</Link>
            <h2>Risk reviews</h2>
            <p>Review manual risk items, assign analysts, and record the final decision.</p>
            <Link className="button secondary" href="/operations/risk-reviews">Open risk reviews</Link>
            <h2>Casework</h2>
            <p>Investigate escalated cases, record evidence, resolve, and close them.</p>
            <Link className="button secondary" href="/operations/cases">Open casework</Link>
            <h2>Ledger</h2>
            <p>Review balances and half-open date-bounded statements for a Ledger account.</p>
            <Link className="button secondary" href="/operations/ledger">Open Ledger</Link>
            <h2>Audit</h2>
            <p>Search Tenant-wide audit evidence using deterministic keyset pagination.</p>
            <Link className="button secondary" href="/operations/audit">Open Audit</Link>
            <h2>Provider operations</h2>
            <p>Inspect durable Provider health and Platform Admin scenario assignments.</p>
            <Link className="button secondary" href="/operations/provider">Open Provider operations</Link>
            <h2>Settlement ingestion</h2>
            <p>Upload, validate, and explicitly process immutable Provider settlement files.</p>
            <Link className="button secondary" href="/operations/settlements">Open settlement ingestion</Link>
            <h2>Reconciliation</h2>
            <p>Compare immutable settlement and internal evidence, inspect discrepancies, and control exact settlement posting.</p>
            <Link className="button secondary" href="/operations/reconciliation">Open reconciliation</Link>
          </div>
        )}
        {tenantResult?.kind === "unavailable" && (
          <div className="panel status error">This Tenant is unavailable to the signed-in user.</div>
        )}
        {tenantResult?.kind === "error" && (
          <div className="panel status error">Core could not verify this Tenant right now.</div>
        )}
        {!supportActive && tenantResult?.kind === "ok" && (
          <div className="panel">
            <div className="eyebrow">Administration</div>
            <h2>Credentials</h2>
            <p>Review non-secret metadata for this Tenant&apos;s sandbox credentials.</p>
            <Link className="button" href="/operations/credentials">Open credentials</Link>
            <h2>Tenant configuration</h2>
            <p>Manage versioned currencies, locale, timezone, and display settings.</p>
            <Link className="button secondary" href="/operations/configuration">Open configuration</Link>
            <h2>Risk configuration</h2>
            <p>Manage Tenant-wide versioned Risk rules and thresholds.</p>
            <Link className="button secondary" href="/operations/risk-configuration">Open Risk configuration</Link>
            <h2>Operational contacts</h2>
            <p>Maintain audited contacts used by Tenant operations.</p>
            <Link className="button secondary" href="/operations/contacts">Open contacts</Link>
            <h2>Merchants</h2>
            <p>Review the Merchants available in the selected Tenant scope.</p>
            <Link className="button secondary" href="/operations/merchants">Open Merchants</Link>
            <h2>Memberships</h2>
            <p>Review Tenant membership, roles, scopes, and invitation status.</p>
            <Link className="button secondary" href="/operations/memberships">Open memberships</Link>
            <h2>Merchant webhooks</h2>
            <p>Create, rotate, revoke, test, and inspect signed webhook deliveries.</p>
            <Link className="button secondary" href="/operations/webhooks">Open webhooks</Link>
            <h2>Platform support</h2>
            <p>Enter an explicit, expiring, read-only support session for a Tenant.</p>
            <Link className="button secondary" href="/operations/support">Open support mode</Link>
          </div>
        )}
        <form action="/api/auth/logout" method="post">
          <input type="hidden" name="csrfToken" value={session.csrfToken} />
          <button className="secondary" type="submit">Log out</button>
        </form>
      </section>
    </main>
  );
}
