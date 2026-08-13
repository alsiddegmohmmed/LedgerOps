import { NextResponse } from "next/server";
import { config } from "../../../../../lib/config";
import { redis } from "../../../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../../../lib/session";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export async function GET(request: Request) {
  const sessionId = request.headers.get("cookie")?.match(new RegExp(`${SESSION_COOKIE}=([^;]+)`))?.[1];
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) {
    return NextResponse.json({ type: "unauthenticated" }, { status: 401 });
  }

  const url = new URL(request.url);
  const tenantId = url.searchParams.get("tenantId") ?? "";
  const supportActive = isSupportSessionActive(session);
  const selectedTenantId = supportActive ? session.supportTenantId : session.selectedTenantId;
  if (!uuid.test(tenantId) || !selectedTenantId || tenantId !== selectedTenantId) {
    return NextResponse.json({ type: "resource_not_found" }, { status: 404 });
  }

  const coreUrl = new URL(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/reports/operational-summary/records`,
    config.coreBaseUrl,
  );
  for (const name of ["metric", "from", "to", "after", "limit"]) {
    const value = url.searchParams.get(name);
    if (value !== null) coreUrl.searchParams.set(name, value);
  }
  for (const merchantId of url.searchParams.getAll("merchantId")) {
    coreUrl.searchParams.append("merchantId", merchantId);
  }

  const headers = new Headers({
    accept: "text/csv",
    authorization: `Bearer ${session.accessToken}`,
  });
  if (supportActive && session.supportSessionId) {
    headers.set("x-support-session-id", session.supportSessionId);
  }

  let coreResponse: Response;
  try {
    coreResponse = await fetch(coreUrl, {
      headers,
      cache: "no-store",
      signal: request.signal,
    });
  } catch {
    return NextResponse.json({ type: "core_unavailable" }, { status: 502 });
  }

  const responseHeaders = new Headers();
  const contentType = coreResponse.headers.get("content-type");
  responseHeaders.set(
    "content-type",
    contentType ?? (coreResponse.ok ? "text/csv; charset=UTF-8" : "application/problem+json"),
  );
  for (const name of ["content-disposition", "cache-control", "x-next-after"]) {
    const value = coreResponse.headers.get(name);
    if (value) responseHeaders.set(name, value);
  }
  return new Response(coreResponse.body, {
    status: coreResponse.status,
    headers: responseHeaders,
  });
}
