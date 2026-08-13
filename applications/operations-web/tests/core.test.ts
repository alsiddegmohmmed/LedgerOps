import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getCredentialPage,
  getTenant,
  getMemberships,
  revokeInvitation,
  getMerchants,
  getOperationalContacts,
  getTenantConfiguration,
  getOperationalSummary,
  getOperationalSummaryRecords,
  updateOperationalContact,
  provisionCredential,
  updateTenantConfiguration,
  startSupportSession,
  requestReversal,
  retryReversal,
} from "../lib/core";

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

  it("reads tenant-scoped Merchants with the bearer token on the server side", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify([
      {
        tenantId: "tenant-id",
        merchantId: "merchant-id",
        name: "Primary Merchant",
        status: "ACTIVE",
        version: 0,
      },
    ]), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getMerchants("tenant-id", "server-only-token")).resolves.toEqual({
      kind: "ok",
      merchants: [{
        tenantId: "tenant-id",
        merchantId: "merchant-id",
        name: "Primary Merchant",
        status: "ACTIVE",
        version: 0,
      }],
    });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain("/api/v1/tenants/tenant-id/merchants");
    expect(init.headers).toEqual({ authorization: "Bearer server-only-token" });
  });

  it("adds the support-session header only for support reads", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      id: "tenant-id",
      name: "Support Tenant",
      defaultCurrency: "SAR",
      defaultLocale: "en-SA",
      status: "ACTIVE",
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getTenant("tenant-id", "server-only-token", {
      supportSessionId: "support-session-id",
    })).resolves.toMatchObject({ kind: "ok" });

    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(init.headers).toEqual({
      authorization: "Bearer server-only-token",
      "x-support-session-id": "support-session-id",
    });
  });

  it("starts support mode through the authenticated Core endpoint", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      supportSessionId: "support-session-id",
      tenantId: "tenant-id",
      startedAt: "2026-08-10T10:00:00Z",
      expiresAt: "2026-08-10T10:30:00Z",
      permission: "support:tenant-read",
    }), { status: 201 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(startSupportSession("server-only-token", {
      tenantId: "tenant-id",
      confirmation: true,
      reason: "Investigate tenant incident",
    })).resolves.toMatchObject({
      kind: "ok",
      result: { permission: "support:tenant-read" },
    });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain("/api/v1/platform/support-sessions");
    expect(init.method).toBe("POST");
    expect(init.headers).toEqual({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
    });
  });

  it("reads tenant-scoped Memberships without exposing invitation secrets", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify([{
      tenantId: "tenant-id",
      membershipId: "membership-id",
      status: "INVITED",
      version: 0,
      initial: false,
      identityLinked: false,
      roleAssignments: [{
        assignmentId: "assignment-id",
        role: "VIEWER",
        scopeMode: "TENANT_WIDE",
        merchantIds: [],
      }],
      invitation: {
        invitationId: "invitation-id",
        intendedEmail: "invite@example.com",
        status: "PENDING",
        expiresAt: "2026-08-17T00:00:00Z",
      },
    }]), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getMemberships("tenant-id", "server-only-token")).resolves.toEqual({
      kind: "ok",
      memberships: [{
        tenantId: "tenant-id",
        membershipId: "membership-id",
        status: "INVITED",
        version: 0,
        initial: false,
        identityLinked: false,
        roleAssignments: [{
          assignmentId: "assignment-id",
          role: "VIEWER",
          scopeMode: "TENANT_WIDE",
          merchantIds: [],
        }],
        invitation: {
          invitationId: "invitation-id",
          intendedEmail: "invite@example.com",
          status: "PENDING",
          expiresAt: "2026-08-17T00:00:00Z",
        },
      }],
    });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain("/api/v1/tenants/tenant-id/memberships");
    expect(init.headers).toEqual({ authorization: "Bearer server-only-token" });
    expect(url).not.toContain("tokenHash");
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

  it("posts invitation revocation with the bearer token only on the server-side request", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      tenantId: "tenant-id",
      membershipId: "membership-id",
      invitationId: "invitation-id",
      membershipStatus: "REVOKED",
      invitationStatus: "REVOKED",
      membershipVersion: 1,
    }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(revokeInvitation(
      "tenant-id",
      "membership-id",
      "server-only-token",
      { confirmation: true, reason: "No longer required" },
    )).resolves.toMatchObject({ kind: "ok", result: { invitationStatus: "REVOKED" } });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain("/api/v1/tenants/tenant-id/memberships/membership-id/invitation/revoke");
    expect(init.method).toBe("POST");
    expect(init.headers).toEqual({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
    });
    expect(JSON.parse(String(init.body))).toEqual({
      confirmation: true,
      reason: "No longer required",
    });
  });

  it("posts the approved Reversal actions with explicit confirmation and reason", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        reversalId: "00000000-0000-4000-8000-000000000005",
        tenantId: "00000000-0000-4000-8000-000000000001",
        paymentId: "00000000-0000-4000-8000-000000000002",
        merchantId: "00000000-0000-4000-8000-000000000003",
        amount: "25.00",
        currency: "SAR",
        status: "REQUESTED",
        requestedBy: "00000000-0000-4000-8000-000000000004",
        requestReason: "Customer requested reversal",
        requestedAt: "2026-08-10T10:00:00Z",
        processingAt: null,
        failedAt: null,
        completedAt: null,
        failureCategory: null,
        version: 0,
      }), { status: 201 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        reversalId: "00000000-0000-4000-8000-000000000005",
        paymentId: "00000000-0000-4000-8000-000000000002",
        attemptId: "00000000-0000-4000-8000-000000000006",
        attemptSequence: 2,
        status: "PROCESSING",
        replay: false,
      }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(requestReversal(
      "00000000-0000-4000-8000-000000000001",
      "00000000-0000-4000-8000-000000000002",
      "server-only-token",
      { confirmation: true, reason: "Customer requested reversal" },
    )).resolves.toMatchObject({ kind: "ok", result: { status: "REQUESTED" } });
    await expect(retryReversal(
      "00000000-0000-4000-8000-000000000001",
      "00000000-0000-4000-8000-000000000005",
      "server-only-token",
      {
        paymentId: "00000000-0000-4000-8000-000000000002",
        previousAttemptId: "00000000-0000-4000-8000-000000000006",
        providerEvidenceId: "00000000-0000-4000-8000-000000000007",
        confirmation: true,
        reason: "Provider proved no acceptance",
      },
    )).resolves.toMatchObject({ kind: "ok", result: { status: "PROCESSING" } });

    const [requestUrl, requestInit] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(requestUrl).toContain("/api/v1/tenants/00000000-0000-4000-8000-000000000001/reversals");
    expect(requestInit.method).toBe("POST");
    expect(JSON.parse(String(requestInit.body))).toEqual({
      paymentId: "00000000-0000-4000-8000-000000000002",
      confirmation: true,
      reason: "Customer requested reversal",
    });
    const [retryUrl, retryInit] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(retryUrl).toContain("/reversals/00000000-0000-4000-8000-000000000005/retry");
    expect(JSON.parse(String(retryInit.body))).toMatchObject({
      previousAttemptId: "00000000-0000-4000-8000-000000000006",
      providerEvidenceId: "00000000-0000-4000-8000-000000000007",
      confirmation: true,
    });
  });

  it("reads and updates configuration through the server-side Core client", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        tenantId: "tenant-id",
        version: 2,
        allowedCurrencies: ["SAR"],
        defaultLocale: "en-SA",
        timezone: "Asia/Riyadh",
        displaySettings: { compactTables: true },
        createdAt: "2026-08-09T00:00:00Z",
      }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        tenantId: "tenant-id",
        version: 3,
        allowedCurrencies: ["SAR", "USD"],
        defaultLocale: "en-SA",
        timezone: "Asia/Riyadh",
        displaySettings: { compactTables: false },
        createdAt: "2026-08-09T00:01:00Z",
      }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getTenantConfiguration("tenant-id", "server-only-token")).resolves.toMatchObject({
      kind: "ok",
      configuration: { version: 2 },
    });
    await expect(updateTenantConfiguration("tenant-id", "server-only-token", {
      allowedCurrencies: ["SAR", "USD"],
      defaultLocale: "en-SA",
      timezone: "Asia/Riyadh",
      displaySettings: { compactTables: false },
      confirmation: true,
      reason: "Update display settings",
    })).resolves.toMatchObject({ kind: "ok", result: { version: 3 } });

    const [, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(init.headers).toEqual({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
    });
    expect(JSON.parse(String(init.body))).toMatchObject({ confirmation: true, reason: "Update display settings" });
  });

  it("keeps operational-contact bearer access on the server-side Core client", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        tenantId: "tenant-id",
        contactId: "contact-id",
        version: 1,
        displayName: "Operations",
        email: "operations@example.com",
        purpose: "settlement",
        active: true,
        createdAt: "2026-08-09T00:00:00Z",
      }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getOperationalContacts("tenant-id", "server-only-token")).resolves.toEqual({
      kind: "ok",
      contacts: [],
    });
    await expect(updateOperationalContact("tenant-id", "contact-id", "server-only-token", {
      displayName: "Operations",
      email: "operations@example.com",
      purpose: "settlement",
      active: true,
      confirmation: true,
      reason: "Add operations contact",
    })).resolves.toMatchObject({ kind: "ok", result: { version: 1 } });

    const [url, init] = fetchMock.mock.calls[1] as unknown as [string, RequestInit];
    expect(url).toContain("/operational-contacts/contact-id");
    expect(init.headers).toEqual({
      authorization: "Bearer server-only-token",
      "content-type": "application/json",
    });
  });
});

describe("Core operational-summary client", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("sends exact instants and repeatable Merchant filters server-side", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      tenantId: "tenant-id",
      period: { from: "2026-08-01T00:00:00Z", to: "2026-08-08T00:00:00Z" },
      scope: { mode: "MERCHANT_SET", merchantIds: ["merchant-a"] },
      asOf: "2026-08-08T10:15:00Z",
      projection: { generation: 3, cursor: 18427 },
      metrics: {},
    }), { status: 200, headers: { "content-type": "application/json" } }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getOperationalSummary("tenant id/encoded", "server-only-token", {
      from: "2026-08-01T00:00:00Z",
      to: "2026-08-08T00:00:00Z",
      merchantIds: ["merchant-a", "merchant-b"],
    }, { supportSessionId: "support-session" })).resolves.toMatchObject({ kind: "ok" });

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const requestUrl = new URL(url);
    expect(requestUrl.pathname).toBe("/api/v1/tenants/tenant%20id%2Fencoded/reports/operational-summary");
    expect(requestUrl.searchParams.get("from")).toBe("2026-08-01T00:00:00Z");
    expect(requestUrl.searchParams.get("to")).toBe("2026-08-08T00:00:00Z");
    expect(requestUrl.searchParams.getAll("merchantId")).toEqual(["merchant-a", "merchant-b"]);
    expect(init.headers).toEqual({
      authorization: "Bearer server-only-token",
      "x-support-session-id": "support-session",
    });
  });

  it("preserves the reporting-not-ready problem code", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      type: "REPORTING_NOT_READY",
    }), { status: 503 })));

    await expect(getOperationalSummary("tenant-id", "token", {
      from: "2026-08-01T00:00:00Z",
      to: "2026-08-08T00:00:00Z",
    })).resolves.toEqual({ kind: "error", status: 503, code: "REPORTING_NOT_READY" });
  });

  it("builds a matching keyset drill-down request", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({
      items: [],
      nextAfter: null,
    }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(getOperationalSummaryRecords("tenant-id", "token", {
      metric: "PAYMENT_SUCCESS",
      from: "2026-08-01T00:00:00Z",
      to: "2026-08-08T00:00:00Z",
      merchantIds: ["merchant-a", "merchant-b"],
      after: "opaque-cursor",
      limit: 25,
    })).resolves.toEqual({ kind: "ok", page: { items: [], nextAfter: null } });

    const [url] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const requestUrl = new URL(url);
    expect(requestUrl.pathname).toContain("/reports/operational-summary/records");
    expect(requestUrl.searchParams.get("metric")).toBe("PAYMENT_SUCCESS");
    expect(requestUrl.searchParams.getAll("merchantId")).toEqual(["merchant-a", "merchant-b"]);
    expect(requestUrl.searchParams.get("after")).toBe("opaque-cursor");
    expect(requestUrl.searchParams.get("limit")).toBe("25");
  });
});
