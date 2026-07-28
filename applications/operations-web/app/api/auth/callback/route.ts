import { NextResponse } from "next/server";
import { config } from "../../../../lib/config";
import { createOauthTransaction, exchangeCode, validateIdToken } from "../../../../lib/oauth";
import { redis } from "../../../../lib/redis";
import { createSession, newOpaqueId, sessionCookie } from "../../../../lib/session";

export async function GET(request: Request) {
  const url = new URL(request.url);
  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  if (!code || !state) return NextResponse.redirect(new URL("/?error=invalid_callback", config.bffOrigin));

  const store = redis();
  const transactionValue = await store.get(`operations-web:oauth:${state}`);
  await store.del(`operations-web:oauth:${state}`);
  if (!transactionValue) return NextResponse.redirect(new URL("/?error=invalid_callback", config.bffOrigin));

  try {
    const transaction = JSON.parse(transactionValue) as ReturnType<typeof createOauthTransaction>;
    const tokens = await exchangeCode(code, transaction.codeVerifier);
    if (!tokens.id_token) throw new Error("OIDC response did not contain an ID token");
    await validateIdToken(tokens.id_token, transaction.nonce);
    const sessionId = await createSession(store, {
      accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token,
      idToken: tokens.id_token,
      expiresAt: Date.now() + tokens.expires_in * 1000,
      csrfToken: newOpaqueId(),
    });
    const response = NextResponse.redirect(new URL("/operations", config.bffOrigin));
    response.cookies.set(sessionCookie(sessionId));
    return response;
  } catch {
    return NextResponse.redirect(new URL("/?error=authentication_failed", config.bffOrigin));
  }
}
