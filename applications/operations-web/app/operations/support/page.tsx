import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getMemberships, getMerchants, getTenant } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../../lib/session";
import { SupportConsole } from "./support-console";

export const dynamic = "force-dynamic";

export default async function SupportPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  const supportActive = Boolean(
    session.supportSessionId
      && session.supportTenantId
      && session.supportExpiresAt
      && session.supportExpiresAt > Date.now(),
  );

  if (!supportActive) {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="eyebrow">Platform support</div>
          <h1>Enter support mode</h1>
          <p>
            Support mode is explicit, read-only, audited, and expires after thirty minutes.
            Choose a Tenant and provide the reason for access.
          </p>
          <SupportConsole csrfToken={session.csrfToken} defaultTenantId={session.selectedTenantId} />
        </section>
      </main>
    );
  }

  const tenantId = session.supportTenantId!;
  const supportSessionId = session.supportSessionId!;
  const [tenantResult, merchantsResult, membershipsResult] = await Promise.all([
    getTenant(tenantId, session.accessToken, { supportSessionId }),
    getMerchants(tenantId, session.accessToken, { supportSessionId }),
    getMemberships(tenantId, session.accessToken, { supportSessionId }),
  ]);

  if ([tenantResult, merchantsResult, membershipsResult].some((result) => result.kind === "unauthenticated")) {
    redirect("/api/auth/login?reason=session");
  }

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <SupportConsole
          csrfToken={session.csrfToken}
          active={{
            tenantId,
            expiresAt: session.supportExpiresAt!,
          }}
          tenant={tenantResult.kind === "ok" ? tenantResult.tenant : null}
          merchants={merchantsResult.kind === "ok" ? merchantsResult.merchants : []}
          memberships={membershipsResult.kind === "ok" ? membershipsResult.memberships : []}
        />
      </section>
    </main>
  );
}
