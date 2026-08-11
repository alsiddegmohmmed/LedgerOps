import { validateSettlementBatch } from "../../../../../lib/core";
import {
  mapCredentialActionResponse,
  requireCredentialActionSession,
} from "../../../../../lib/settlement-actions";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ batchVersionId: string }> },
) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  const { batchVersionId } = await params;
  const result = await validateSettlementBatch(
    access.session.selectedTenantId!,
    batchVersionId,
    access.session.accessToken,
  );
  return mapCredentialActionResponse(result, 200, "settlement_validation_failed");
}
