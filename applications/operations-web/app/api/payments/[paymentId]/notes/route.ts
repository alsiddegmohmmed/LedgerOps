import { NextResponse } from "next/server";
import { addPaymentNote } from "../../../../../lib/core";
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
  const content = requiredText(body?.content, 4000);
  if (!body || !validPaymentId || !content) return invalidCredentialActionRequest();

  const result = await addPaymentNote(
    access.session.selectedTenantId!,
    validPaymentId,
    access.session.accessToken,
    content,
  );
  if (result.kind === "unauthenticated") {
    return NextResponse.json(
      { type: "session_expired", login: "/api/auth/login?reason=session" },
      { status: 401 },
    );
  }
  if (result.kind === "error") {
    return NextResponse.json(
      { type: result.code ?? "payment_note_failed" },
      { status: result.status },
    );
  }
  return NextResponse.json(result.result, { status: 201 });
}
