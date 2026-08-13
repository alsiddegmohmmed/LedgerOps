import { randomBytes } from "node:crypto";
import { config } from "./config";
import { refreshAccessToken } from "./oauth";

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

const refreshesInFlight = new Map<string, Promise<BffSession | null>>();

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
    const session = JSON.parse(value) as BffSession;
    if (!session.refreshToken || session.expiresAt > Date.now() + config.sessionRefreshLeewaySeconds * 1000) {
      return session;
    }

    const existingRefresh = refreshesInFlight.get(sessionId);
    if (existingRefresh) return existingRefresh;

    const refresh = refreshSession(store, sessionId, session);
    refreshesInFlight.set(sessionId, refresh);
    try {
      return await refresh;
    } finally {
      refreshesInFlight.delete(sessionId);
    }
  } catch {
    return null;
  }
}

async function refreshSession(store: SessionStore, sessionId: string, session: BffSession) {
  try {
    const tokens = await refreshAccessToken(session.refreshToken!);
    if (!tokens.access_token || !Number.isFinite(tokens.expires_in) || tokens.expires_in <= 0) {
      return session;
    }
    const latestValue = await store.get(key(sessionId));
    let latestSession = session;
    if (latestValue) {
      try {
        latestSession = JSON.parse(latestValue) as BffSession;
      } catch {
        // Preserve the already validated session when the latest value is malformed.
      }
    }
    const refreshed: BffSession = {
      ...latestSession,
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token ?? latestSession.refreshToken ?? session.refreshToken,
      idToken: tokens.id_token ?? latestSession.idToken ?? session.idToken,
      expiresAt: Date.now() + tokens.expires_in * 1000,
    };
    await store.set(key(sessionId), JSON.stringify(refreshed), "EX", config.sessionTtlSeconds);
    return refreshed;
  } catch {
    return session;
  }
}

export async function updateSession(store: SessionStore, sessionId: string, session: BffSession) {
  await store.set(key(sessionId), JSON.stringify(session), "EX", config.sessionTtlSeconds);
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
