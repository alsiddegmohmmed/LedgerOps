import { afterEach, describe, expect, it, vi } from "vitest";
import { getCredentialPage } from "../lib/core";

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
});
