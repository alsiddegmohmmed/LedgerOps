import { executeReconciliationRun } from "../../../lib/core";
import {
  invalidReconciliationActionRequest,
  mapCredentialActionResponse,
  readCredentialActionBody,
  requiredInstant,
  requiredText,
  requiredUuid,
  requireCredentialActionSession,
} from "../../../lib/reconciliation-actions";

export async function POST(request: Request) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  const body = await readCredentialActionBody(request);
  if (!body || body.confirmation !== true) return invalidReconciliationActionRequest();
  const batchVersionId = requiredUuid(body.batchVersionId);
  const rulesVersion = requiredText(body.rulesVersion, 64);
  const sourceCutoff = requiredInstant(body.sourceCutoff);
  if (!batchVersionId || !rulesVersion || !sourceCutoff) {
    return invalidReconciliationActionRequest();
  }
  const result = await executeReconciliationRun(
    access.session.selectedTenantId!,
    access.session.accessToken,
    { batchVersionId, rulesVersion, sourceCutoff, confirmation: true },
  );
  return mapCredentialActionResponse(result, 201, "reconciliation_run_failed");
}
