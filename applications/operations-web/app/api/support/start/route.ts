import { NextResponse } from "next/server";
import { startSupportSession } from "../../../../lib/core";
import {
  invalidSupportRequest,
  persistSupportMode,
  readSupportBody,
  requiredSupportReason,
  requiredSupportUuid,
  requireSupportActionSession,
} from "../../../../lib/support-actions";

export async function POST(request: Request) {
  const access = await requireSupportActionSession(request);
  if ("response" in access) return access.response;

  const body = await readSupportBody(request);
  const tenantId = requiredSupportUuid(body?.tenantId);
  const reason = requiredSupportReason(body?.reason);
  if (!body || !tenantId || !reason || body.confirmation !== true) {
    return invalidSupportRequest();
  }

  const result = await startSupportSession(access.session.accessToken, {
    tenantId,
    confirmation: true,
    reason,
  });
  if (result.kind === "unauthenticated") {
    return NextResponse.json(
      { type: "session_expired", login: "/api/auth/login?reason=session" },
      { status: 401 },
    );
  }
  if (result.kind === "error") {
    return NextResponse.json(
      { type: result.code ?? "support_session_start_failed" },
      { status: result.status },
    );
  }

  await persistSupportMode(access.sessionId, access.session, {
    supportSessionId: result.result.supportSessionId,
    tenantId: result.result.tenantId,
    expiresAt: result.result.expiresAt,
  });
  return NextResponse.json(result.result, { status: 201 });
}
