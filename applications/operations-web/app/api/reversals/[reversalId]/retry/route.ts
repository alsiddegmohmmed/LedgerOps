import { NextResponse } from "next/server";
import { retryReversal } from "../../../../../lib/core";
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
  context: { params: Promise<{ reversalId: string }> },
) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  if (isSupportSessionActive(access.session)) {
    return NextResponse.json({ type: "support_read_only" }, { status: 403 });
  }

  const { reversalId } = await context.params;
  const body = await readCredentialActionBody(request);
  const validReversalId = requiredUuid(reversalId);
  const paymentId = requiredUuid(body?.paymentId);
  const previousAttemptId = requiredUuid(body?.previousAttemptId);
  const providerEvidenceId = requiredUuid(body?.providerEvidenceId);
  const reason = requiredText(body?.reason, 512);
  if (!body || !validReversalId || !paymentId || !previousAttemptId
      || !providerEvidenceId || !reason || body.confirmation !== true) {
    return invalidCredentialActionRequest();
  }

  const result = await retryReversal(
    access.session.selectedTenantId!,
    validReversalId,
    access.session.accessToken,
    {
      paymentId,
      previousAttemptId,
      providerEvidenceId,
      confirmation: true,
      reason,
    },
  );
  if (result.kind === "unauthenticated") {
    return NextResponse.json(
      { type: "session_expired", login: "/api/auth/login?reason=session" },
      { status: 401 },
    );
  }
  if (result.kind === "error") {
    return NextResponse.json(
      { type: result.code ?? "reversal_retry_failed" },
      { status: result.status },
    );
  }
  return NextResponse.json(result.result, { status: 200 });
}
