import { prepareReconciliationPosting } from "../../../../../../lib/core";
import {
  invalidReconciliationActionRequest,
  mapCredentialActionResponse,
  readCredentialActionBody,
  requiredText,
  requiredUuid,
  requireCredentialActionSession,
} from "../../../../../../lib/reconciliation-actions";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ runId: string }> },
) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  const body = await readCredentialActionBody(request);
  if (!body || body.confirmation !== true) return invalidReconciliationActionRequest();
  const batchFamilyId = requiredUuid(body.batchFamilyId);
  const reason = requiredText(body.reason, 512);
  if (!batchFamilyId || !reason) return invalidReconciliationActionRequest();
  const { runId } = await params;
  if (!requiredUuid(runId)) return invalidReconciliationActionRequest();
  const result = await prepareReconciliationPosting(
    access.session.selectedTenantId!, runId, access.session.accessToken,
    { batchFamilyId, confirmation: true, reason },
  );
  return mapCredentialActionResponse(result, 200, "reconciliation_posting_prepare_failed");
}
