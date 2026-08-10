import { triggerWebhookTest } from "../../../../lib/core";
import {
  invalidWebhookActionRequest,
  mapWebhookActionResponse,
  readWebhookActionBody,
  requiredWebhookText,
  requiredWebhookUuid,
  requireWebhookActionSession,
  validWebhookPayload,
} from "../../../../lib/webhook-actions";

export async function POST(request: Request) {
  const access = await requireWebhookActionSession(request);
  if ("response" in access) return access.response;
  const body = await readWebhookActionBody(request);
  const merchantId = requiredWebhookUuid(body?.merchantId);
  const endpointId = requiredWebhookUuid(body?.endpointId);
  const eventType = requiredWebhookText(body?.eventType, 100);
  if (!body || !merchantId || !endpointId || !eventType || !validWebhookPayload(body.payload)) {
    return invalidWebhookActionRequest();
  }
  const result = await triggerWebhookTest(
    access.session.selectedTenantId!, merchantId, endpointId, access.session.accessToken,
    { eventType, payload: body.payload },
  );
  return mapWebhookActionResponse(result, 202, "webhook_test_failed");
}
