import { randomBytes, createHash } from "node:crypto";
import { jwtVerify, createRemoteJWKSet } from "jose";
import { config, oidcEndpoints } from "./config";

export type OauthTransaction = {
  nonce: string;
  codeVerifier: string;
  createdAt: number;
};

export function createOauthTransaction(): OauthTransaction & { state: string } {
  return {
    state: randomBytes(32).toString("base64url"),
    nonce: randomBytes(32).toString("base64url"),
    codeVerifier: randomBytes(48).toString("base64url"),
    createdAt: Date.now(),
  };
}

export function codeChallenge(codeVerifier: string) {
  return createHash("sha256").update(codeVerifier).digest("base64url");
}

export function authorizationUrl(transaction: OauthTransaction & { state: string }) {
  const url = new URL(oidcEndpoints.authorization);
  url.search = new URLSearchParams({
    client_id: config.keycloakClientId,
    redirect_uri: config.redirectUri,
    response_type: "code",
    scope: "openid profile",
    state: transaction.state,
    nonce: transaction.nonce,
    code_challenge: codeChallenge(transaction.codeVerifier),
    code_challenge_method: "S256",
  }).toString();
  return url;
}

export async function exchangeCode(code: string, codeVerifier: string) {
  const response = await fetch(oidcEndpoints.token, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: config.keycloakClientId,
      redirect_uri: config.redirectUri,
      code,
      code_verifier: codeVerifier,
    }),
    cache: "no-store",
  });
  if (!response.ok) throw new Error("OIDC token exchange failed");
  return (await response.json()) as {
    access_token: string;
    refresh_token?: string;
    id_token?: string;
    expires_in: number;
  };
}

export async function validateIdToken(idToken: string, nonce: string) {
  const jwks = createRemoteJWKSet(new URL(oidcEndpoints.jwks));
  const { payload } = await jwtVerify(idToken, jwks, {
    issuer: config.keycloakIssuer,
    audience: config.keycloakClientId,
  });
  if (payload.nonce !== nonce) throw new Error("OIDC nonce validation failed");
}
