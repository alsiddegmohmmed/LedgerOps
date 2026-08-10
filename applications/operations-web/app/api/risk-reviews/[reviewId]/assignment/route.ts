import { assignRiskReview } from "../../../../../lib/core";
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
  context: { params: Promise<{ reviewId: string }> },
) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  if (isSupportSessionActive(access.session)) {
    return NextResponse.json({ type: "support_read_only" }, { status: 403 });
  }
  const { reviewId } = await context.params;
  const body = await readCredentialActionBody(request);
  const analystId = requiredUuid(body?.analystId);
  const reason = requiredText(body?.reason, 512);
  const priority = body?.priority;
  if (!body || !requiredUuid(reviewId) || !analystId || !reason
      || typeof priority !== "number" || !Number.isInteger(priority) || priority < 0) {
    return invalidCredentialActionRequest();
  }
  const result = await assignRiskReview(
    access.session.selectedTenantId!, reviewId, access.session.accessToken,
    { analystId, priority, reason },
  );
  return mapCredentialActionResponse(result, 200, "risk_review_assignment_failed");
}
