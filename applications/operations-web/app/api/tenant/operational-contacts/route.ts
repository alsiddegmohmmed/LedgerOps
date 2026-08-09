import { updateOperationalContact } from "../../../../lib/core";
import {
  invalidOperationalContactRequest,
  mapOperationalContactResponse,
  readOperationalContactBody,
  requiredText,
  requiredUuid,
  requireOperationalContactSession,
} from "../../../../lib/operational-contact-actions";

export async function PUT(request: Request) {
  const access = await requireOperationalContactSession(request);
  if ("response" in access) return access.response;

  const body = await readOperationalContactBody(request);
  const contactId = requiredUuid(body?.contactId);
  const displayName = requiredText(body?.displayName, 160);
  const email = requiredText(body?.email, 320);
  const purpose = requiredText(body?.purpose, 120);
  const reason = requiredText(body?.reason, 512);
  if (
    !body
    || !contactId
    || !displayName
    || !email
    || !purpose
    || typeof body.active !== "boolean"
    || body.confirmation !== true
    || !reason
  ) {
    return invalidOperationalContactRequest();
  }

  const result = await updateOperationalContact(
    access.session.selectedTenantId!,
    contactId,
    access.session.accessToken,
    {
      displayName,
      email,
      purpose,
      active: body.active,
      confirmation: true,
      reason,
    },
  );
  return mapOperationalContactResponse(result, 200, "operational_contact_update_failed");
}
