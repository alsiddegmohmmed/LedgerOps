import { beforeEach, describe, expect, it, vi } from "vitest";

const state = vi.hoisted(() => ({ values: new Map<string, string>() }));

vi.mock("../lib/redis", () => ({
  redis: () => ({
    get: async (key: string) => state.values.get(key) ?? null,
  }),
}));

import {
  readCredentialActionBody,
  requireCredentialActionSession,
} from "../lib/credential-actions";

const session = {
  accessToken: "server-only-token",
  expiresAt: Date.now() + 60_000,
  csrfToken: "csrf-token",
  selectedTenantId: "00000000-0000-4000-8000-000000000001",
};

describe("credential action BFF boundary", () => {
  beforeEach(() => {
    state.values.clear();
    state.values.set(
      "operations-web:session:session-id",
      JSON.stringify(session),
    );
  });

  it("rejects cross-origin action requests", async () => {
    const result = await requireCredentialActionSession(new Request(
      "http://localhost:3001/api/credentials/create",
      { method: "POST", headers: { origin: "https://attacker.example" } },
    ));

    expect("response" in result).toBe(true);
    if ("response" in result) expect(result.response.status).toBe(403);
  });

  it("rejects missing or invalid CSRF tokens", async () => {
    const request = (token?: string) => new Request(
      "http://localhost:3001/api/credentials/create",
      {
        method: "POST",
        headers: {
          origin: "http://localhost:3001",
          cookie: "__Host-ledgerops_session=session-id",
          ...(token ? { "x-csrf-token": token } : {}),
        },
      },
    );

    const missing = await requireCredentialActionSession(request());
    const invalid = await requireCredentialActionSession(request("wrong-token"));
    expect("response" in missing && missing.response.status).toBe(403);
    expect("response" in invalid && invalid.response.status).toBe(403);
  });

  it("returns the server-side session only after origin and CSRF validation", async () => {
    const result = await requireCredentialActionSession(new Request(
      "http://localhost:3001/api/credentials/create",
      {
        method: "POST",
        headers: {
          origin: "http://localhost:3001",
          cookie: "__Host-ledgerops_session=session-id",
          "x-csrf-token": "csrf-token",
        },
      },
    ));

    expect(result).toEqual({ session });
  });

  it("accepts only JSON objects for action bodies", async () => {
    await expect(readCredentialActionBody(new Request("http://localhost:3001", {
      method: "POST",
      body: JSON.stringify({ confirmation: true }),
    }))).resolves.toEqual({ confirmation: true });
    await expect(readCredentialActionBody(new Request("http://localhost:3001", {
      method: "POST",
      body: "not-json",
    }))).resolves.toBeNull();
  });
});
