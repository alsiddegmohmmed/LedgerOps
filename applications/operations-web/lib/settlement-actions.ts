import { mapCredentialActionResponse, readCredentialActionBody, requireCredentialActionSession } from "./credential-actions";

export { readCredentialActionBody, requireCredentialActionSession };

export function invalidSettlementActionRequest() {
  return Response.json({ type: "invalid_settlement_request" }, { status: 400 });
}

export { mapCredentialActionResponse };
