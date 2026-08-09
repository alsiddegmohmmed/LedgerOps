import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getMerchants, getTenant } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../../lib/session";

export const dynamic = "force-dynamic";

export default async function MerchantsPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  if (!session.selectedTenantId) {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            Choose and verify a Tenant before opening Merchants.
          </div>
        </section>
      </main>
    );
  }

  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            This Tenant is unavailable or Core could not verify it right now.
          </div>
        </section>
      </main>
    );
  }

  const merchantsResult = await getMerchants(session.selectedTenantId, session.accessToken);
  if (merchantsResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (merchantsResult.kind !== "ok") {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            You cannot read Merchants for this Tenant right now.
          </div>
        </section>
      </main>
    );
  }

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Administration / Merchants</div>
        <h1>Merchants</h1>
        <p>{tenantResult.tenant.name}. Results are filtered by your effective Merchant scope.</p>
        {merchantsResult.merchants.length === 0 ? (
          <div className="panel status">No Merchants are visible in the selected Tenant scope.</div>
        ) : (
          <div className="panel">
            <table>
              <caption className="sr-only">Visible Merchants</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Status</th>
                  <th scope="col">Version</th>
                  <th scope="col">Merchant ID</th>
                </tr>
              </thead>
              <tbody>
                {merchantsResult.merchants.map((merchant) => (
                  <tr key={merchant.merchantId}>
                    <th scope="row">{merchant.name}</th>
                    <td>{merchant.status}</td>
                    <td>{merchant.version}</td>
                    <td className="monospace">{merchant.merchantId}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
