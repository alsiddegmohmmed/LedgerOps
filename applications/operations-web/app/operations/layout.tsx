import { cookies } from "next/headers";
import type { ReactNode } from "react";
import { getTenant, getTenantConfiguration } from "../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../lib/formatting";
import { redis } from "../../lib/redis";
import { isSupportSessionActive, readSession, SESSION_COOKIE } from "../../lib/session";
import { OperationsShell } from "./operations-shell";
import { SupportModeBanner } from "./support-mode-banner";

export const dynamic = "force-dynamic";

export default async function OperationsLayout({ children }: { children: ReactNode }) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  const supportActive = Boolean(session && isSupportSessionActive(session));
  const activeTenantId = supportActive ? session?.supportTenantId : session?.selectedTenantId;
  const supportReadOptions = supportActive && session?.supportSessionId
    ? { supportSessionId: session.supportSessionId }
    : {};
  const activeTenantResult = activeTenantId && session
    ? await getTenant(activeTenantId, session.accessToken, supportReadOptions)
    : null;
  const configurationResult = activeTenantResult?.kind === "ok" && session
    ? await getTenantConfiguration(activeTenantResult.tenant.id, session.accessToken, supportReadOptions)
    : null;
  const displayLocale = configurationResult?.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : activeTenantResult?.kind === "ok"
      ? activeTenantResult.tenant.defaultLocale
      : "en";
  const displayTimezone = configurationResult?.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;
  const tenant = activeTenantResult?.kind === "ok"
    ? {
      id: activeTenantResult.tenant.id,
      name: activeTenantResult.tenant.name,
      status: activeTenantResult.tenant.status,
    }
    : null;

  return (
    <OperationsShell
      csrfToken={session?.csrfToken}
      supportActive={supportActive}
      tenant={tenant}
    >
      {supportActive && session && (
        <SupportModeBanner
          csrfToken={session.csrfToken}
          expiresAt={session.supportExpiresAt!}
          expiresAtLabel={formatOperationsDateTime(new Date(session.supportExpiresAt!).toISOString(), displayLocale, displayTimezone)}
        />
      )}
      {children}
    </OperationsShell>
  );
}
