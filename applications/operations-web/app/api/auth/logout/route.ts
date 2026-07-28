import { NextResponse } from "next/server";
import { config } from "../../../../lib/config";
import { validCsrfToken, validSameOrigin } from "../../../../lib/csrf";
import { redis } from "../../../../lib/redis";
import { deleteSession, readSession, SESSION_COOKIE, sessionCookie } from "../../../../lib/session";

export async function POST(request: Request) {
  if (!validSameOrigin(request)) return NextResponse.json({ type: "invalid_origin" }, { status: 403 });
  const sessionId = request.headers.get("cookie")?.match(new RegExp(`${SESSION_COOKIE}=([^;]+)`))?.[1];
  const session = await readSession(redis(), sessionId);
  const csrfToken = (await request.formData()).get("csrfToken");
  if (!session || !validCsrfToken(session, typeof csrfToken === "string" ? csrfToken : null)) {
    return NextResponse.json({ type: "invalid_csrf" }, { status: 403 });
  }
  await deleteSession(redis(), sessionId);
  const response = NextResponse.redirect(new URL("/", config.bffOrigin));
  response.cookies.set(sessionCookie("", 0));
  return response;
}
