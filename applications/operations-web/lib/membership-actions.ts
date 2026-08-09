import { NextResponse } from "next/server";
import { validCsrfToken, validSameOrigin } from "./csrf";
import { redis } from "./redis";
import { isSessionExpired, readSession, SESSION_COOKIE, type BffSession } from "./session";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function requireMembershipActionSession(request: Request): Promise<
  { session: BffSession } | { response: NextResponse }
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
  if (!session || isSessionExpired(session)) {
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
  if (!session.selectedTenantId) {
    return { response: NextResponse.json({ type: "tenant_not_selected" }, { status: 400 }) };
  }
  return { session };
}

export async function readMembershipActionBody(request: Request) {
  try {
    const body = await request.json();
    if (!body || typeof body !== "object" || Array.isArray(body)) return null;
    return body as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function requiredMembershipUuid(value: unknown) {
  return typeof value === "string" && UUID.test(value) ? value : null;
}

export function requiredMembershipText(value: unknown, maximum: number) {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  return normalized && normalized.length <= maximum ? normalized : null;
}

export function invalidMembershipActionRequest() {
  return NextResponse.json({ type: "invalid_request" }, { status: 400 });
}

export function mapMembershipActionResponse(
  result: {
    kind: "unauthenticated";
  } | {
    kind: "error";
    status: number;
    code?: string;
  } | {
    kind: "ok";
    result: unknown;
  },
  successStatus: number,
) {
  if (result.kind === "unauthenticated") {
    return NextResponse.json(
      { type: "session_expired", login: "/api/auth/login?reason=session" },
      { status: 401 },
    );
  }
  if (result.kind === "error") {
    return NextResponse.json(
      { type: result.code ?? "invitation_revocation_failed" },
      { status: result.status },
    );
  }
  return NextResponse.json(result.result, { status: successStatus });
}
