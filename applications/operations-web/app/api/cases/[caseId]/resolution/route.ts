import { resolveCase } from "../../../../../lib/core";
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

const RESOLUTIONS = new Set([
  "RISK_APPROVE", "RISK_REJECT", "PROVIDER_ERROR", "INTERNAL_PROCESSING_ERROR",
  "DUPLICATE_EXTERNAL_RECORD", "EXPECTED_TIMING_DIFFERENCE", "APPROVED_CORRECTION", "FALSE_POSITIVE",
]);

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
  const resolution = typeof body?.resolution === "string" ? body.resolution : "";
  const note = requiredText(body?.note, 4000);
  const confirmation = body?.confirmation === true;
  if (!body || !requiredUuid(caseId) || !note || !RESOLUTIONS.has(resolution)
      || !confirmation) return invalidCredentialActionRequest();
  const result = await resolveCase(
    access.session.selectedTenantId!, caseId, access.session.accessToken,
    { resolution, note, confirmation },
  );
  return mapCredentialActionResponse(result, 200, "case_resolution_failed");
}
