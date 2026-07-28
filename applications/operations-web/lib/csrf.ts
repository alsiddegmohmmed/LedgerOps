import { timingSafeEqual } from "node:crypto";
import type { BffSession } from "./session";
import { config } from "./config";

export function validCsrfToken(session: BffSession, supplied: string | null | undefined) {
  if (!supplied) return false;
  const expected = Buffer.from(session.csrfToken);
  const actual = Buffer.from(supplied);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

export function validSameOrigin(request: Request) {
  const origin = request.headers.get("origin");
  return !origin || origin === config.bffOrigin;
}
