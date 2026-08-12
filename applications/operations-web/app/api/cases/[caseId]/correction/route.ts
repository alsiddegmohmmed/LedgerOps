import { requestCaseCorrection } from "../../../../../lib/core";
import {
  invalidCredentialActionRequest,
  mapCredentialActionResponse,
  readCredentialActionBody,
  requiredText,
  requiredUuid,
  requireCredentialActionSession,
} from "../../../../../lib/credential-actions";
import { isSupportSessionActive } from "../../../../../lib/session";
import { NextResponse } from "next/server";

export async function POST(
  request: Request,
  context: { params: Promise<{ caseId: string }> },
) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  if (isSupportSessionActive(access.session)) {
    return NextResponse.json({ type: "support_read_only" }, { status: 403 });
  }
  const { caseId } = await context.params;
  const body = await readCredentialActionBody(request);
  const settlementPostingId = requiredUuid(body?.settlementPostingId);
  const originalLedgerTransactionId = requiredUuid(body?.originalLedgerTransactionId);
  const reason = requiredText(body?.reason, 2000);
  const confirmation = body?.confirmation === true;
  if (!body || !requiredUuid(caseId) || !settlementPostingId
      || !originalLedgerTransactionId || !reason || !confirmation) {
    return invalidCredentialActionRequest();
  }
  const result = await requestCaseCorrection(
    access.session.selectedTenantId!, caseId, access.session.accessToken,
    { settlementPostingId, originalLedgerTransactionId, reason, confirmation },
  );
  return mapCredentialActionResponse(result, 200, "case_correction_failed");
}
