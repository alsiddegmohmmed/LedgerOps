import { NextResponse } from "next/server";
import { requestReversal } from "../../../../../lib/core";
import {
  invalidCredentialActionRequest,
  readCredentialActionBody,
  requiredText,
  requiredUuid,
  requireCredentialActionSession,
} from "../../../../../lib/credential-actions";
import { isSupportSessionActive } from "../../../../../lib/session";

export async function POST(
  request: Request,
  context: { params: Promise<{ paymentId: string }> },
) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  if (isSupportSessionActive(access.session)) {
    return NextResponse.json({ type: "support_read_only" }, { status: 403 });
  }

  const { paymentId } = await context.params;
  const body = await readCredentialActionBody(request);
  const validPaymentId = requiredUuid(paymentId);
  const reason = requiredText(body?.reason, 512);
  if (!body || !validPaymentId || !reason || body.confirmation !== true) {
    return invalidCredentialActionRequest();
  }

  const result = await requestReversal(
    access.session.selectedTenantId!,
    validPaymentId,
    access.session.accessToken,
    { confirmation: true, reason },
  );
  if (result.kind === "unauthenticated") {
    return NextResponse.json(
      { type: "session_expired", login: "/api/auth/login?reason=session" },
      { status: 401 },
    );
  }
  if (result.kind === "error") {
    return NextResponse.json(
      { type: result.code ?? "reversal_request_failed" },
      { status: result.status },
    );
  }
  return NextResponse.json(result.result, { status: 201 });
}
