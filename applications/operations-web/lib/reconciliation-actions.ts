import { NextResponse } from "next/server";
import {
  mapCredentialActionResponse,
  readCredentialActionBody,
  requiredText,
  requiredUuid,
  requireCredentialActionSession,
} from "./credential-actions";

export {
  mapCredentialActionResponse,
  readCredentialActionBody,
  requiredText,
  requiredUuid,
  requireCredentialActionSession,
};

export function invalidReconciliationActionRequest() {
  return NextResponse.json({ type: "invalid_reconciliation_request" }, { status: 400 });
}

export function requiredInstant(value: unknown) {
  if (typeof value !== "string" || !value.trim()) return null;
  const normalized = value.trim();
  return Number.isNaN(Date.parse(normalized)) ? null : normalized;
}
