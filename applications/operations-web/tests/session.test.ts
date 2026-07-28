import { describe, expect, it } from "vitest";
import { createSession, deleteSession, readSession, sessionCookie, updateSession, type BffSession, type SessionStore } from "../lib/session";

function store(): SessionStore {
  const values = new Map<string, string>();
  return {
    get: async (key) => values.get(key) ?? null,
    set: async (key, value) => { values.set(key, value); },
    del: async (key) => { values.delete(key); },
  };
}

const session: BffSession = { accessToken: "server-only", expiresAt: Date.now() + 60_000, csrfToken: "csrf" };

describe("opaque BFF sessions", () => {
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
});
