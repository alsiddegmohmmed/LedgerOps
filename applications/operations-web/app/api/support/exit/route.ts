import { NextResponse } from "next/server";
import { clearSupportMode, requireSupportActionSession } from "../../../../lib/support-actions";
import { redis } from "../../../../lib/redis";
import { updateSession } from "../../../../lib/session";

export async function POST(request: Request) {
  const access = await requireSupportActionSession(request);
  if ("response" in access) return access.response;
  await updateSession(redis(), access.sessionId, clearSupportMode(access.session));
  return NextResponse.json({ status: "support_mode_exited" });
}
