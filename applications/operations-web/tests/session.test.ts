import { beforeEach, describe, expect, it, vi } from "vitest";
import { createSession, deleteSession, isSupportSessionActive, readSession, sessionCookie, updateSession, type BffSession, type SessionStore } from "../lib/session";

const refreshState = vi.hoisted(() => ({ refreshAccessToken: vi.fn() }));
vi.mock("../lib/oauth", () => ({ refreshAccessToken: refreshState.refreshAccessToken }));

function store(): SessionStore {
  const values = new Map<string, string>();
  return {
    get: async (key) => values.get(key) ?? null,
    set: async (key, value) => { values.set(key, value); },
    del: async (key) => { values.delete(key); },
  };
}

const session: BffSession = { accessToken: "server-only", expiresAt: Date.now() + 60_000, csrfToken: "csrf" };

describe.sequential("opaque BFF sessions", () => {
  beforeEach(() => refreshState.refreshAccessToken.mockReset());

  it("stores and retrieves session data without exposing it in the cookie", async () => {
    const sessions = store();
    const id = await createSession(sessions, session);
    expect(id).not.toContain("server-only");
    expect(await readSession(sessions, id)).toEqual(session);
    const cookie = sessionCookie(id);
    expect(cookie).toMatchObject({ name: "__Host-ledgerops_session", httpOnly: true, secure: true, sameSite: "lax", path: "/" });
  });

  it("updates and deletes the server-side session", async () => {
    const sessions = store();
    const id = await createSession(sessions, session);
    const selected = { ...session, selectedTenantId: "tenant" };
    await updateSession(sessions, id, selected);
    expect(await readSession(sessions, id)).toEqual(selected);
    await deleteSession(sessions, id);
    expect(await readSession(sessions, id)).toBeNull();
  });

  it("recognizes an active support session without exposing its bearer token", () => {
    expect(isSupportSessionActive({
      ...session,
      supportSessionId: "support-session",
      supportTenantId: "tenant",
      supportExpiresAt: Date.now() + 30_000,
    })).toBe(true);
    expect(isSupportSessionActive({
      ...session,
      supportSessionId: "support-session",
      supportTenantId: "tenant",
      supportExpiresAt: Date.now() - 1,
    })).toBe(false);
  });

  it("refreshes an expiring access token with the server-side refresh token", async () => {
    const sessions = store();
    const id = await createSession(sessions, {
      ...session,
      accessToken: "expired-soon",
      refreshToken: "refresh-token",
      expiresAt: Date.now() + 1_000,
    });
    refreshState.refreshAccessToken.mockResolvedValue({
      access_token: "refreshed-access",
      refresh_token: "rotated-refresh",
      expires_in: 300,
    });

    const refreshed = await readSession(sessions, id);

    expect(refreshState.refreshAccessToken).toHaveBeenCalledWith("refresh-token");
    expect(refreshed).toMatchObject({
      accessToken: "refreshed-access",
      refreshToken: "rotated-refresh",
    });
    expect(refreshed!.expiresAt).toBeGreaterThan(Date.now());
  });

  it("preserves a Tenant selected while the token refresh is in flight", async () => {
    const sessions = store();
    const id = await createSession(sessions, {
      ...session,
      refreshToken: "refresh-token",
      expiresAt: Date.now() + 1_000,
    });
    refreshState.refreshAccessToken.mockImplementation(async () => {
      await sessions.set(
        `operations-web:session:${id}`,
        JSON.stringify({ ...session, refreshToken: "refresh-token", selectedTenantId: "tenant" }),
        "EX",
        3600,
      );
      return { access_token: "refreshed-access", expires_in: 300 };
    });

    const refreshed = await readSession(sessions, id);

    expect(refreshed).toMatchObject({ accessToken: "refreshed-access", selectedTenantId: "tenant" });
  });

  it("keeps the expired session for the normal login redirect when refresh fails", async () => {
    const sessions = store();
    const id = await createSession(sessions, {
      ...session,
      refreshToken: "invalid-refresh-token",
      expiresAt: Date.now() - 1,
    });
    refreshState.refreshAccessToken.mockResolvedValue({} as never);

    const result = await readSession(sessions, id);

    expect(result).toMatchObject({ accessToken: "server-only", refreshToken: "invalid-refresh-token" });
    expect(result!.expiresAt).toBeLessThanOrEqual(Date.now());
  });
});
