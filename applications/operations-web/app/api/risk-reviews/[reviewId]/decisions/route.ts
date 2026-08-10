import { decideRiskReview } from "../../../../../lib/core";
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

const DECISIONS = new Set(["APPROVE", "REJECT", "ESCALATE"]);

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
  const reason = requiredText(body?.reason, 4000);
  const decision = typeof body?.decision === "string" ? body.decision : "";
  if (!body || !requiredUuid(reviewId) || !reason || !DECISIONS.has(decision)) {
    return invalidCredentialActionRequest();
  }
  const result = await decideRiskReview(
    access.session.selectedTenantId!, reviewId, access.session.accessToken,
    { decision: decision as "APPROVE" | "REJECT" | "ESCALATE", reason },
  );
  return mapCredentialActionResponse(result, 200, "risk_review_decision_failed");
}
