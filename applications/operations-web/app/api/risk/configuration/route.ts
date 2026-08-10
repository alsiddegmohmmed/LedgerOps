import { updateRiskConfiguration, type CoreRiskRuleConfiguration } from "../../../../lib/core";
import {
  invalidWebhookActionRequest,
  mapWebhookActionResponse,
  readWebhookActionBody,
  requiredWebhookText,
  requireWebhookActionSession,
} from "../../../../lib/webhook-actions";

function validRules(value: unknown): value is CoreRiskRuleConfiguration[] {
  return Array.isArray(value)
    && value.length > 0
    && value.every((rule) => {
      if (typeof rule !== "object" || rule === null || Array.isArray(rule)) return false;
      const candidate = rule as Record<string, unknown>;
      return typeof candidate.currency === "string"
        && /^[A-Z]{3}$/.test(candidate.currency)
        && (candidate.amountThreshold == null || typeof candidate.amountThreshold === "number" || typeof candidate.amountThreshold === "string")
        && Number.isInteger(candidate.scoreContribution)
        && Number(candidate.scoreContribution) >= 1
        && Number(candidate.scoreContribution) <= 100
        && typeof candidate.enabled === "boolean";
    });
}

export async function PUT(request: Request) {
  const access = await requireWebhookActionSession(request);
  if ("response" in access) return access.response;
  const body = await readWebhookActionBody(request);
  const reviewThreshold = Number(body?.reviewThreshold);
  const rejectThreshold = Number(body?.rejectThreshold);
  const expectedVersion = body?.expectedVersion == null || body.expectedVersion === "" ? null : Number(body.expectedVersion);
  const reason = requiredWebhookText(body?.reason, 512);
  if (
    !body
    || !Number.isInteger(reviewThreshold) || reviewThreshold < 1 || reviewThreshold > 99
    || !Number.isInteger(rejectThreshold) || rejectThreshold < 2 || rejectThreshold > 100
    || (expectedVersion !== null && (!Number.isInteger(expectedVersion) || expectedVersion < 1))
    || !validRules(body.rules)
    || !reason
    || body.confirmation !== true
  ) {
    return invalidWebhookActionRequest();
  }
  const result = await updateRiskConfiguration(
    access.session.selectedTenantId!, access.session.accessToken,
    { reviewThreshold, rejectThreshold, rules: body.rules, expectedVersion, confirmation: true, reason },
  );
  return mapWebhookActionResponse(result, 200, "risk_configuration_update_failed");
}
