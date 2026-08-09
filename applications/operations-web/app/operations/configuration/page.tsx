import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getTenant, getTenantConfiguration } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../../lib/session";
import { ConfigurationForm } from "./configuration-form";

export const dynamic = "force-dynamic";

export default async function TenantConfigurationPage() {
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
            Choose and verify a Tenant before opening configuration.
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

  const configurationResult = await getTenantConfiguration(
    session.selectedTenantId,
    session.accessToken,
  );
  if (configurationResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (configurationResult.kind === "unavailable" || configurationResult.kind === "error") {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            You cannot read configuration for this Tenant right now.
          </div>
        </section>
      </main>
    );
  }

  const configuration = configurationResult.kind === "ok" ? configurationResult.configuration : null;
  const initial = configuration
    ? {
        allowedCurrencies: configuration.allowedCurrencies,
        defaultLocale: configuration.defaultLocale,
        timezone: configuration.timezone,
        displaySettings: JSON.stringify(configuration.displaySettings, null, 2),
      }
    : {
        allowedCurrencies: [tenantResult.tenant.defaultCurrency],
        defaultLocale: tenantResult.tenant.defaultLocale,
        timezone: "Asia/Riyadh",
        displaySettings: "{}",
      };

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Administration / Tenant configuration</div>
        <h1>Tenant configuration</h1>
        <p>{tenantResult.tenant.name}. Changes are versioned and audited by Core.</p>
        {configuration ? (
          <div className="panel status">
            Current version: <strong>{configuration.version}</strong> · saved {new Date(configuration.createdAt).toLocaleString()}
          </div>
        ) : (
          <div className="panel status">
            No configuration version exists yet. Saving this form will create version 1.
          </div>
        )}
        <ConfigurationForm csrfToken={session.csrfToken} initial={initial} />
      </section>
    </main>
  );
}
