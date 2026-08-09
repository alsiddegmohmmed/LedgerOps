import { provisionCredential } from "../../../../lib/core";
import {
  invalidCredentialActionRequest,
  mapCredentialActionResponse,
  readCredentialActionBody,
  requiredText,
  requiredUuid,
  requireCredentialActionSession,
} from "../../../../lib/credential-actions";

export async function POST(request: Request) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;
  const body = await readCredentialActionBody(request);
  const merchantId = requiredUuid(body?.merchantId);
  const label = requiredText(body?.label, 255);
  const reason = requiredText(body?.reason, 512);
  if (!body || !merchantId || !label || !reason || body.confirmation !== true) {
    return invalidCredentialActionRequest();
  }
  const result = await provisionCredential(
    access.session.selectedTenantId!,
    access.session.accessToken,
    { merchantId, label, confirmation: true, reason },
  );
  return mapCredentialActionResponse(result, 201);
}
