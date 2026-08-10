import { transitionCase } from "../../../../../lib/core";
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

const STATUSES = new Set(["INVESTIGATING", "AWAITING_INFORMATION", "RESOLVED", "REOPENED"]);

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
  const target = typeof body?.target === "string" ? body.target : "";
  const reason = requiredText(body?.reason, 512);
  if (!body || !requiredUuid(caseId) || !reason || !STATUSES.has(target)) return invalidCredentialActionRequest();
  const result = await transitionCase(
    access.session.selectedTenantId!, caseId, access.session.accessToken,
    { target: target as "INVESTIGATING" | "AWAITING_INFORMATION" | "RESOLVED" | "REOPENED", reason },
  );
  return mapCredentialActionResponse(result, 200, "case_transition_failed");
}
