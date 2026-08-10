import { assignProviderScenario } from "../../../../../lib/core";
import {
  invalidWebhookActionRequest,
  mapWebhookActionResponse,
  readWebhookActionBody,
  requiredWebhookText,
  requiredWebhookUuid,
  requireWebhookActionSession,
} from "../../../../../lib/webhook-actions";

const SCOPES = new Set(["GLOBAL", "TENANT", "PAYMENT"]);

export async function POST(request: Request) {
  const access = await requireWebhookActionSession(request);
  if ("response" in access) return access.response;
  const body = await readWebhookActionBody(request);
  const scope = requiredWebhookText(body?.scope, 20);
  const tenantId = body?.tenantId == null || body.tenantId === "" ? null : requiredWebhookUuid(body.tenantId);
  const paymentId = body?.paymentId == null || body.paymentId === "" ? null : requiredWebhookUuid(body.paymentId);
  const profileId = requiredWebhookUuid(body?.profileId);
  const profileVersion = Number(body?.profileVersion);
  if (
    !body || !scope || !SCOPES.has(scope)
    || (body.tenantId != null && body.tenantId !== "" && !tenantId)
    || (body.paymentId != null && body.paymentId !== "" && !paymentId)
    || !profileId || !Number.isInteger(profileVersion) || profileVersion < 1
    || body.confirmation !== true
  ) {
    return invalidWebhookActionRequest();
  }
  const result = await assignProviderScenario(access.session.accessToken, {
    scope,
    tenantId,
    paymentId,
    profileId,
    profileVersion,
  });
  return mapWebhookActionResponse(result, 200, "provider_scenario_assignment_failed");
}
