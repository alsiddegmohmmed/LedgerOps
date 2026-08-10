import { createWebhookEndpoint } from "../../../../lib/core";
import {
  invalidWebhookActionRequest,
  mapWebhookActionResponse,
  readWebhookActionBody,
  requiredWebhookText,
  requiredWebhookUuid,
  requireWebhookActionSession,
  validWebhookEventTypes,
} from "../../../../lib/webhook-actions";

export async function POST(request: Request) {
  const access = await requireWebhookActionSession(request);
  if ("response" in access) return access.response;
  const body = await readWebhookActionBody(request);
  const merchantId = requiredWebhookUuid(body?.merchantId);
  const label = requiredWebhookText(body?.label, 120);
  const endpointUrl = requiredWebhookText(body?.endpointUrl, 2000);
  if (!body || !merchantId || !label || !endpointUrl || !validWebhookEventTypes(body.allowedEventTypes)) {
    return invalidWebhookActionRequest();
  }
  const result = await createWebhookEndpoint(
    access.session.selectedTenantId!,
    merchantId,
    access.session.accessToken,
    { label, endpointUrl, allowedEventTypes: body.allowedEventTypes },
  );
  return mapWebhookActionResponse(result, 201, "webhook_create_failed");
}
