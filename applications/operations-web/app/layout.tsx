import type { Metadata } from "next";
import { cookies } from "next/headers";
import "./globals.css";
import { getTenant, getTenantConfiguration } from "../lib/core";
import { redis } from "../lib/redis";
import { operationsDirection, operationsHtmlLocale, operationsLanguage } from "../lib/locale";
import { isSupportSessionActive, readSession, SESSION_COOKIE } from "../lib/session";

export const metadata: Metadata = {
  title: "LedgerOps Operations",
  description: "Authenticated LedgerOps Operations Web shell",
};

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = sessionId ? await readSession(redis(), sessionId) : null;
  const tenantId = session && isSupportSessionActive(session)
    ? session.supportTenantId
    : session?.selectedTenantId;
  const tenantResult = tenantId && session
    ? await getTenant(
        tenantId,
        session.accessToken,
        isSupportSessionActive(session) && session.supportSessionId
          ? { supportSessionId: session.supportSessionId }
          : {},
      )
    : null;
  const supportOptions = session && isSupportSessionActive(session) && session.supportSessionId
    ? { supportSessionId: session.supportSessionId }
    : {};
  const configurationResult = tenantResult?.kind === "ok" && session
    ? await getTenantConfiguration(tenantResult.tenant.id, session.accessToken, supportOptions)
    : null;
  const locale = operationsHtmlLocale(
    configurationResult?.kind === "ok"
      ? configurationResult.configuration.defaultLocale
      : tenantResult?.kind === "ok"
        ? tenantResult.tenant.defaultLocale
        : undefined,
  );
  const language = operationsLanguage(locale);

  return (
    <html lang={locale} dir={operationsDirection(language)}>
      <body>{children}</body>
    </html>
  );
}
