import { createProviderScenarioProfile } from "../../../../../lib/core";
import {
  invalidWebhookActionRequest,
  mapWebhookActionResponse,
  readWebhookActionBody,
  requiredWebhookText,
  requiredWebhookUuid,
  requireWebhookActionSession,
} from "../../../../../lib/webhook-actions";

const OUTCOMES = new Set([
  "SUCCESS", "DECLINE", "ACCEPTED", "PENDING", "TIMEOUT_AFTER_ACCEPTANCE",
  "TEMPORARY_FAILURE_SAFE_TO_RESUBMIT", "TEMPORARY_FAILURE_STATUS_RECOVERY",
]);
const WEBHOOK_MODES = new Set(["NORMAL", "DELAYED", "DUPLICATE", "MISSING", "INVALID_SIGNATURE", "OUT_OF_ORDER"]);
const SETTLEMENT_MODES = new Set(["EXACT", "MISSING", "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "STATUS_MISMATCH", "DUPLICATE_RECORD", "DATE_MISMATCH"]);

export async function POST(request: Request) {
  const access = await requireWebhookActionSession(request);
  if ("response" in access) return access.response;
  const body = await readWebhookActionBody(request);
  const profileId = body?.profileId == null || body.profileId === "" ? null : requiredWebhookUuid(body.profileId);
  const expectedPreviousVersion = body?.expectedPreviousVersion == null || body.expectedPreviousVersion === ""
    ? null
    : Number(body.expectedPreviousVersion);
  const submissionOutcome = requiredWebhookText(body?.submissionOutcome, 80);
  const webhookMode = requiredWebhookText(body?.webhookMode, 80);
  const settlementMode = requiredWebhookText(body?.settlementMode, 80);
  const fixtureId = body?.fixtureId == null || body.fixtureId === "" ? null : requiredWebhookText(body.fixtureId, 255);
  const delayMillis = Number(body?.delayMillis ?? 0);
  const invalidExpectedPreviousVersion = expectedPreviousVersion === null
    ? body?.expectedPreviousVersion != null && body.expectedPreviousVersion !== ""
    : !Number.isInteger(expectedPreviousVersion) || expectedPreviousVersion < 1;
  if (
    !body
    || (body.profileId != null && body.profileId !== "" && !profileId)
    || invalidExpectedPreviousVersion
    || !submissionOutcome || !OUTCOMES.has(submissionOutcome)
    || !webhookMode || !WEBHOOK_MODES.has(webhookMode)
    || !settlementMode || !SETTLEMENT_MODES.has(settlementMode)
    || !Number.isInteger(delayMillis) || delayMillis < 0 || delayMillis > 300000
    || (body.fixtureId != null && body.fixtureId !== "" && !fixtureId)
    || body.confirmation !== true
  ) {
    return invalidWebhookActionRequest();
  }
  const result = await createProviderScenarioProfile(access.session.accessToken, {
    profileId,
    expectedPreviousVersion,
    submissionOutcome,
    webhookMode,
    settlementMode,
    delayMillis,
    fixtureId,
    parameters: {},
  });
  return mapWebhookActionResponse(result, 200, "provider_scenario_profile_failed");
}
