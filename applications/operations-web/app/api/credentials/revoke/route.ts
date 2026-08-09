import { revokeCredential } from "../../../../lib/core";
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
  const credentialId = requiredUuid(body?.credentialId);
  const reason = requiredText(body?.reason, 512);
  if (!body || !credentialId || !reason || body.confirmation !== true) {
    return invalidCredentialActionRequest();
  }
  const result = await revokeCredential(
    access.session.selectedTenantId!,
    credentialId,
    access.session.accessToken,
    { confirmation: true, reason },
  );
  return mapCredentialActionResponse(result, 200);
}
