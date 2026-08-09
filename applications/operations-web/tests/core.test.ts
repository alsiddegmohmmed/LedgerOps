import { afterEach, describe, expect, it, vi } from "vitest";
import { getCredentialPage, provisionCredential } from "../lib/core";

describe("Core credential metadata client", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("keeps the bearer token server-side and forwards pagination filters", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      items: [],
      nextCursor: null,
    }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getCredentialPage(
      "tenant id/encoded",
      "server-only-token",
      { cursor: "opaque cursor", merchantId: "merchant-id", status: "ACTIVE", limit: 25 },
    )).resolves.toEqual({ kind: "ok", page: { items: [], nextCursor: null } });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const requestUrl = new URL(url);
    expect(requestUrl.pathname).toBe("/api/v1/tenants/tenant%20id%2Fencoded/credentials");
    expect(requestUrl.searchParams.get("cursor")).toBe("opaque cursor");
    expect(requestUrl.searchParams.get("merchantId")).toBe("merchant-id");
    expect(requestUrl.searchParams.get("status")).toBe("ACTIVE");
    expect(requestUrl.searchParams.get("limit")).toBe("25");
    expect(init.headers).toEqual({ authorization: "Bearer server-only-token" });
  });

  it("maps an expired Core session to an unauthenticated result", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 401 })));

    await expect(getCredentialPage("tenant", "token")).resolves.toEqual({
      kind: "unauthenticated",
    });
  });

  it("posts credential actions with the bearer token only on the server-side request", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      credentialId: "00000000-0000-4000-8000-000000000001",
      operationId: "00000000-0000-4000-8000-000000000002",
      tenantId: "00000000-0000-4000-8000-000000000003",
      merchantId: "00000000-0000-4000-8000-000000000004",
      keycloakClientId: "client-id",
      clientSecret: "one-time-secret",
      status: "ACTIVE",
    }), { status: 201, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(provisionCredential(
      "00000000-0000-4000-8000-000000000003",
      "server-only-token",
      {
        merchantId: "00000000-0000-4000-8000-000000000004",
        label: "Checkout",
        confirmation: true,
        reason: "Initial integration",
      },
    )).resolves.toMatchObject({ kind: "ok" });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain("/api/v1/tenants/00000000-0000-4000-8000-000000000003/credentials");
    expect(init.method).toBe("POST");
    expect(init.headers).toEqual({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
    });
    expect(JSON.parse(String(init.body))).toMatchObject({ label: "Checkout", confirmation: true });
  });
});
