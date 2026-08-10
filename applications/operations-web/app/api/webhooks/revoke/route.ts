import { revokeWebhookEndpoint } from "../../../../lib/core";
import {
  invalidWebhookActionRequest,
  mapWebhookActionResponse,
  readWebhookActionBody,
  requiredWebhookUuid,
  requireWebhookActionSession,
} from "../../../../lib/webhook-actions";

export async function POST(request: Request) {
  const access = await requireWebhookActionSession(request);
  if ("response" in access) return access.response;
  const body = await readWebhookActionBody(request);
  const merchantId = requiredWebhookUuid(body?.merchantId);
  const endpointId = requiredWebhookUuid(body?.endpointId);
  if (!body || !merchantId || !endpointId || body.confirmation !== true) {
    return invalidWebhookActionRequest();
  }
  const result = await revokeWebhookEndpoint(
    access.session.selectedTenantId!, merchantId, endpointId, access.session.accessToken,
  );
  return mapWebhookActionResponse(result, 200, "webhook_revoke_failed");
}
