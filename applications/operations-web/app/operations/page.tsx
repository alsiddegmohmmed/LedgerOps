import { redirect } from "next/navigation";
import { getTenant } from "../../lib/core";
import { redis } from "../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../lib/session";
import { cookies } from "next/headers";
import { TenantSelector } from "./tenant-selector";

export const dynamic = "force-dynamic";

export default async function OperationsPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  const tenantResult = session.selectedTenantId
    ? await getTenant(session.selectedTenantId, session.accessToken)
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
        {tenantResult?.kind === "ok" && (
          <div className="panel status">
            <div className="eyebrow">Core-verified Tenant</div>
            <h2>{tenantResult.tenant.name}</h2>
            <p>{tenantResult.tenant.status} · {tenantResult.tenant.defaultCurrency} · {tenantResult.tenant.defaultLocale}</p>
          </div>
        )}
        {tenantResult?.kind === "unavailable" && (
          <div className="panel status error">This Tenant is unavailable to the signed-in user.</div>
        )}
        {tenantResult?.kind === "error" && (
          <div className="panel status error">Core could not verify this Tenant right now.</div>
        )}
        {tenantResult?.kind === "ok" && (
          <div className="panel">
            <div className="eyebrow">Administration</div>
            <h2>Credentials</h2>
            <p>Review non-secret metadata for this Tenant&apos;s sandbox credentials.</p>
            <a className="button" href="/operations/credentials">Open credentials</a>
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
