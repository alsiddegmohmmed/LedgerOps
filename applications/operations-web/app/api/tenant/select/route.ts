import { NextResponse } from "next/server";
import { config } from "../../../../lib/config";
import { validCsrfToken, validSameOrigin } from "../../../../lib/csrf";
import { getTenant } from "../../../../lib/core";
import { redis } from "../../../../lib/redis";
import { readSession, SESSION_COOKIE, updateSession } from "../../../../lib/session";

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function POST(request: Request) {
  if (!validSameOrigin(request)) return NextResponse.json({ type: "invalid_origin" }, { status: 403 });
  const sessionId = request.headers.get("cookie")?.match(new RegExp(`${SESSION_COOKIE}=([^;]+)`))?.[1];
  const session = await readSession(redis(), sessionId);
  if (!session || session.expiresAt <= Date.now()) return NextResponse.redirect(new URL("/api/auth/login?reason=session", config.bffOrigin));
  const form = await request.formData();
  const tenantId = String(form.get("tenantId") ?? "");
  const csrfToken = form.get("csrfToken");
  if (!validCsrfToken(session, typeof csrfToken === "string" ? csrfToken : null) || !uuid.test(tenantId)) {
    return NextResponse.json({ type: "invalid_request" }, { status: 400 });
  }
  const result = await getTenant(tenantId, session.accessToken);
  if (result.kind === "unauthenticated") return NextResponse.redirect(new URL("/api/auth/login?reason=session", config.bffOrigin));
  if (result.kind === "unavailable") return NextResponse.json({ type: "tenant_unavailable" }, { status: 404 });
  if (result.kind === "error") return NextResponse.json({ type: "core_unavailable" }, { status: 502 });
  await updateSession(redis(), sessionId!, { ...session, selectedTenantId: result.tenant.id });
  return NextResponse.redirect(new URL("/operations", config.bffOrigin));
}
