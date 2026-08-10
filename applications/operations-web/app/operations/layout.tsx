import { cookies } from "next/headers";
import type { ReactNode } from "react";
import { redis } from "../../lib/redis";
import { isSupportSessionActive, readSession, SESSION_COOKIE } from "../../lib/session";
import { SupportModeBanner } from "./support-mode-banner";

export const dynamic = "force-dynamic";

export default async function OperationsLayout({ children }: { children: ReactNode }) {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  const supportActive = session && isSupportSessionActive(session);

  return (
    <>
      {supportActive && (
        <SupportModeBanner
          csrfToken={session.csrfToken}
          expiresAt={session.supportExpiresAt!}
        />
      )}
      {children}
    </>
  );
}
