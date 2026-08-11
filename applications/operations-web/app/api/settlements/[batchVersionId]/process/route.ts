import { processSettlementBatch } from "../../../../../lib/core";
import {
  invalidSettlementActionRequest,
  mapCredentialActionResponse,
  readCredentialActionBody,
  requireCredentialActionSession,
} from "../../../../../lib/settlement-actions";

export async function POST(
  request: Request,
  { params }: { params: Promise<{ batchVersionId: string }> },
) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  const body = await readCredentialActionBody(request);
  if (!body || body.confirmation !== true) return invalidSettlementActionRequest();
  const { batchVersionId } = await params;
  const result = await processSettlementBatch(
    access.session.selectedTenantId!,
    batchVersionId,
    access.session.accessToken,
  );
  return mapCredentialActionResponse(result, 200, "settlement_processing_failed");
}
