import { revokeInvitation } from "../../../../lib/core";
import {
  invalidMembershipActionRequest,
  mapMembershipActionResponse,
  readMembershipActionBody,
  requiredMembershipText,
  requiredMembershipUuid,
  requireMembershipActionSession,
} from "../../../../lib/membership-actions";

export async function POST(request: Request) {
  const access = await requireMembershipActionSession(request);
  if ("response" in access) return access.response;

  const body = await readMembershipActionBody(request);
  const membershipId = requiredMembershipUuid(body?.membershipId);
  const reason = requiredMembershipText(body?.reason, 512);
  if (!body || !membershipId || !reason || body.confirmation !== true) {
    return invalidMembershipActionRequest();
  }

  const result = await revokeInvitation(
    access.session.selectedTenantId!,
    membershipId,
    access.session.accessToken,
    { confirmation: true, reason },
  );
  return mapMembershipActionResponse(result, 200);
}
