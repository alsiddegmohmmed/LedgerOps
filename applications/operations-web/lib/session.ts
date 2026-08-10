import { randomBytes } from "node:crypto";
import { config } from "./config";

export const SESSION_COOKIE = "__Host-ledgerops_session";

export type BffSession = {
  accessToken: string;
  refreshToken?: string;
  idToken?: string;
  expiresAt: number;
  csrfToken: string;
  selectedTenantId?: string;
  supportSessionId?: string;
  supportTenantId?: string;
  supportExpiresAt?: number;
};

export type SessionStore = {
  get(key: string): Promise<string | null>;
  set(key: string, value: string, mode: "EX", ttl: number): Promise<unknown>;
  del(key: string): Promise<unknown>;
};

export function isSessionExpired(session: BffSession) {
  return session.expiresAt <= Date.now();
}

export function isSupportSessionActive(session: BffSession) {
  return Boolean(
    session.supportSessionId
      && session.supportTenantId
      && session.supportExpiresAt
      && session.supportExpiresAt > Date.now(),
  );
}

function key(sessionId: string) {
  return `operations-web:session:${sessionId}`;
}

export function newOpaqueId() {
  return randomBytes(32).toString("base64url");
}

export async function createSession(store: SessionStore, session: BffSession) {
  const sessionId = newOpaqueId();
  await store.set(key(sessionId), JSON.stringify(session), "EX", config.sessionTtlSeconds);
  return sessionId;
}

export async function readSession(store: SessionStore, sessionId: string | undefined) {
  if (!sessionId) return null;
  const value = await store.get(key(sessionId));
  if (!value) return null;
  try {
    return JSON.parse(value) as BffSession;
  } catch {
    return null;
  }
}

export async function updateSession(store: SessionStore, sessionId: string, session: BffSession) {
  const ttl = Math.max(1, Math.ceil((session.expiresAt - Date.now()) / 1000));
  await store.set(key(sessionId), JSON.stringify(session), "EX", Math.min(ttl, config.sessionTtlSeconds));
}

export function sessionCookie(sessionId: string, maxAge = config.sessionTtlSeconds) {
  return {
    name: SESSION_COOKIE,
    value: sessionId,
    httpOnly: true,
    secure: true,
    sameSite: "lax" as const,
    path: "/",
    maxAge,
  };
}

export async function deleteSession(store: SessionStore, sessionId: string | undefined) {
  if (sessionId) await store.del(key(sessionId));
}
