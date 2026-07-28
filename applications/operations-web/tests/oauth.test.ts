import { describe, expect, it } from "vitest";
import { authorizationUrl, codeChallenge, createOauthTransaction } from "../lib/oauth";

describe("OIDC authorization code flow", () => {
  it("creates a stateful PKCE authorization request", () => {
    const transaction = createOauthTransaction();
    const url = authorizationUrl(transaction);
    expect(transaction.state).toHaveLength(43);
    expect(transaction.nonce).toHaveLength(43);
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("state")).toBe(transaction.state);
    expect(url.searchParams.get("nonce")).toBe(transaction.nonce);
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("code_challenge")).toBe(codeChallenge(transaction.codeVerifier));
  });
});
