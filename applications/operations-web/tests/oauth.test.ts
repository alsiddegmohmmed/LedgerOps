import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

const joseState = vi.hoisted(() => ({ localJwks: undefined as unknown }));
const redisState = vi.hoisted(() => ({
  values: new Map<string, string>(),
  deleted: [] as string[],
  store: undefined as unknown,
}));

vi.mock("jose", async () => {
  const actual = await vi.importActual<typeof import("jose")>("jose");
  return { ...actual, createRemoteJWKSet: vi.fn(() => joseState.localJwks) };
});

vi.mock("../lib/redis", () => {
  redisState.store = {
    get: async (key: string) => redisState.values.get(key) ?? null,
    set: async (key: string, value: string) => { redisState.values.set(key, value); },
    del: async (key: string) => { redisState.deleted.push(key); redisState.values.delete(key); },
  };
  return { redis: () => redisState.store };
});

import { generateKeyPair, exportJWK, createLocalJWKSet, SignJWT } from "jose";
import { authorizationUrl, codeChallenge, createOauthTransaction, validateIdToken } from "../lib/oauth";

const ISSUER = "http://localhost:8180/realms/ledgerops";

describe("OIDC authorization code flow", () => {
  let callbackGet: (request: Request) => Promise<Response>;
  let idToken: string;

  beforeAll(async () => {
    const { privateKey, publicKey } = await generateKeyPair("RS256");
    const jwk = await exportJWK(publicKey);
    joseState.localJwks = createLocalJWKSet({ keys: [{ ...jwk, alg: "RS256", kid: "slice1-test" }] });
    idToken = await new SignJWT({ nonce: "token-nonce" })
      .setProtectedHeader({ alg: "RS256", kid: "slice1-test" })
      .setIssuer(ISSUER)
      .setAudience("operations-web")
      .setIssuedAt()
      .setExpirationTime("5m")
      .sign(privateKey);
    callbackGet = (await import("../app/api/auth/callback/route")).GET;
  });

  beforeEach(() => {
    redisState.values.clear();
    redisState.deleted.length = 0;
    vi.unstubAllGlobals();
  });

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

  it("reaches production ID-token nonce validation and consumes the transaction", async () => {
    const state = "nonce-route-state";
    redisState.values.set(`operations-web:oauth:${state}`, JSON.stringify({
      state, nonce: "stored-nonce", codeVerifier: "original-verifier", createdAt: Date.now(),
    }));
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      access_token: "access", refresh_token: "refresh", id_token: idToken, expires_in: 300,
    }), { status: 200, headers: { "content-type": "application/json" } })));

    await expect(validateIdToken(idToken, "token-nonce")).resolves.toBeUndefined();

    const response = await callbackGet(new Request(`http://localhost:3001/api/auth/callback?state=${state}&code=valid-code`));
    expect(response.headers.get("location")).toContain("error=authentication_failed");
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(redisState.deleted).toContain(`operations-web:oauth:${state}`);

    const replay = await callbackGet(new Request(`http://localhost:3001/api/auth/callback?state=${state}&code=valid-code`));
    expect(replay.headers.get("location")).toContain("error=invalid_callback");
  });

  it("accepts the original verifier and creates a session", async () => {
    const state = "pkce-route-success-state";
    const originalVerifier = "original-verifier";
    let submittedVerifier: string | null = null;
    redisState.values.set(`operations-web:oauth:${state}`, JSON.stringify({
      state, nonce: "token-nonce", codeVerifier: originalVerifier, createdAt: Date.now(),
    }));
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      submittedVerifier = new URLSearchParams(String(init?.body)).get("code_verifier");
      if (submittedVerifier === originalVerifier) {
        return new Response(JSON.stringify({
          access_token: "access", refresh_token: "refresh", id_token: idToken, expires_in: 300,
        }), { status: 200, headers: { "content-type": "application/json" } });
      }
      return new Response(JSON.stringify({ error: "invalid_grant", error_description: "PKCE code verifier mismatch" }), {
        status: 400, headers: { "content-type": "application/json" },
      });
    }));

    const response = await callbackGet(new Request(`http://localhost:3001/api/auth/callback?state=${state}&code=valid-code`));
    expect(submittedVerifier).toBe(originalVerifier);
    expect(response.headers.get("location")).toContain("/operations");
    expect(response.headers.get("set-cookie")).toContain("__Host-ledgerops_session=");
    expect([...redisState.values.keys()].some((key) => key.startsWith("operations-web:session:"))).toBe(true);
  });

  it("submits the altered verifier and reaches the token endpoint PKCE rejection", async () => {
    const state = "pkce-route-state";
    const originalVerifier = "original-verifier";
    const alteredVerifier = "altered-verifier";
    redisState.values.set(`operations-web:oauth:${state}`, JSON.stringify({
      state, nonce: "token-nonce", codeVerifier: originalVerifier, createdAt: Date.now(),
    }));
    const storedTransaction = JSON.parse(redisState.values.get(`operations-web:oauth:${state}`)!);
    storedTransaction.codeVerifier = alteredVerifier;
    redisState.values.set(`operations-web:oauth:${state}`, JSON.stringify(storedTransaction));
    let submittedVerifier: string | null = null;
    vi.stubGlobal("fetch", vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      submittedVerifier = new URLSearchParams(String(init?.body)).get("code_verifier");
      if (submittedVerifier === originalVerifier) {
        return new Response(JSON.stringify({
          access_token: "access", refresh_token: "refresh", id_token: idToken, expires_in: 300,
        }), { status: 200, headers: { "content-type": "application/json" } });
      }
      return new Response(JSON.stringify({ error: "invalid_grant", error_description: "PKCE code verifier mismatch" }), {
          status: 400, headers: { "content-type": "application/json" },
        });
    }));

    const response = await callbackGet(new Request(`http://localhost:3001/api/auth/callback?state=${state}&code=valid-code`));
    expect(submittedVerifier).toBe(alteredVerifier);
    expect(response.headers.get("location")).toContain("error=authentication_failed");
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(redisState.deleted).toContain(`operations-web:oauth:${state}`);
    expect([...redisState.values.keys()].some((key) => key.startsWith("operations-web:session:"))).toBe(false);

    const replay = await callbackGet(new Request(`http://localhost:3001/api/auth/callback?state=${state}&code=valid-code`));
    expect(replay.headers.get("location")).toContain("error=invalid_callback");
  });
});
