import { NextResponse } from "next/server";
import { validCsrfToken, validSameOrigin } from "./csrf";
import { redis } from "./redis";
import { isSessionExpired, readSession, SESSION_COOKIE, updateSession, type BffSession } from "./session";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function requireSupportActionSession(request: Request): Promise<
  { sessionId: string; session: BffSession } | { response: NextResponse }
> {
  if (!validSameOrigin(request)) {
    return { response: NextResponse.json({ type: "invalid_origin" }, { status: 403 }) };
  }
  const sessionId = request.headers.get("cookie")
    ?.split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${SESSION_COOKIE}=`))
    ?.slice(SESSION_COOKIE.length + 1);
  const session = await readSession(redis(), sessionId);
  if (!sessionId || !session || isSessionExpired(session)) {
    return {
      response: NextResponse.json(
        { type: "session_expired", login: "/api/auth/login?reason=session" },
        { status: 401 },
      ),
    };
  }
  if (!validCsrfToken(session, request.headers.get("x-csrf-token"))) {
    return { response: NextResponse.json({ type: "invalid_csrf" }, { status: 403 }) };
  }
  return { sessionId, session };
}

export async function readSupportBody(request: Request) {
  try {
    const body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) return null;
    return body as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function requiredSupportUuid(value: unknown) {
  return typeof value === "string" && UUID.test(value) ? value : null;
}

export function requiredSupportReason(value: unknown) {
  if (typeof value !== "string") return null;
  const reason = value.trim();
  return reason.length > 0 && reason.length <= 512 ? reason : null;
}

export function invalidSupportRequest() {
  return NextResponse.json({ type: "invalid_request" }, { status: 400 });
}

export function clearSupportMode(session: BffSession): BffSession {
  const next = { ...session };
  delete next.supportSessionId;
  delete next.supportTenantId;
  delete next.supportExpiresAt;
  return next;
}

export async function persistSupportMode(
  sessionId: string,
  session: BffSession,
  support: { supportSessionId: string; tenantId: string; expiresAt: string },
) {
  const expiresAt = Date.parse(support.expiresAt);
  if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
    throw new Error("Core returned an invalid support-session expiry");
  }
  await updateSession(redis(), sessionId, {
    ...session,
    selectedTenantId: support.tenantId,
    supportSessionId: support.supportSessionId,
    supportTenantId: support.tenantId,
    supportExpiresAt: expiresAt,
  });
}
