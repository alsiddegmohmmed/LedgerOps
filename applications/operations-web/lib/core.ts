import { config } from "./config";

export type CoreTenant = {
  id: string;
  name: string;
  defaultCurrency: string;
  defaultLocale: string;
  status: string;
};

export type CoreCredentialMetadata = {
  credentialId: string;
  tenantId: string;
  merchantId: string;
  label: string;
  keycloakClientId: string;
  status: string;
  provisioningOperationId: string;
  replacesCredentialId: string | null;
  disclosureStatus: string;
  createdAt: string;
  updatedAt: string;
};

export type CoreCredentialPage = {
  items: CoreCredentialMetadata[];
  nextCursor: string | null;
};

export type CoreCredentialActionResult = {
  previousCredentialId?: string;
  credentialId: string;
  operationId: string;
  tenantId: string;
  merchantId: string;
  keycloakClientId: string;
  clientSecret?: string;
  status: string;
};

export async function getTenant(tenantId: string, accessToken: string) {
  const response = await fetch(`${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}`, {
    headers: { authorization: `Bearer ${accessToken}` },
    cache: "no-store",
  });
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, tenant: (await response.json()) as CoreTenant };
}

export async function getCredentialPage(
  tenantId: string,
  accessToken: string,
  options: { cursor?: string; merchantId?: string; status?: string; limit?: number } = {},
) {
  const params = new URLSearchParams();
  if (options.cursor) params.set("cursor", options.cursor);
  if (options.merchantId) params.set("merchantId", options.merchantId);
  if (options.status) params.set("status", options.status);
  if (options.limit !== undefined) params.set("limit", String(options.limit));
  const query = params.toString();
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/credentials${query ? `?${query}` : ""}`,
    {
      headers: { authorization: `Bearer ${accessToken}` },
      cache: "no-store",
    },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, page: (await response.json()) as CoreCredentialPage };
}

export type CoreCredentialActionResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CoreCredentialActionResult };

async function postCredentialAction(
  path: string,
  accessToken: string,
  body: Record<string, unknown>,
  successStatus: number,
): Promise<CoreCredentialActionResponse> {
  const response = await fetch(`${config.coreBaseUrl}${path}`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${accessToken}`,
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  if (response.status === 401) return { kind: "unauthenticated" };
  if (!response.ok) {
    let code: string | undefined;
    try {
      const problem = await response.json() as { code?: unknown };
      if (typeof problem.code === "string") code = problem.code;
    } catch {
      // The BFF maps an unparseable Core response to a generic action error.
    }
    return { kind: "error", status: response.status, code };
  }
  if (response.status !== successStatus) {
    return { kind: "error", status: 502, code: "unexpected_core_response" };
  }
  return { kind: "ok", result: await response.json() as CoreCredentialActionResult };
}

export function provisionCredential(
  tenantId: string,
  accessToken: string,
  body: { merchantId: string; label: string; confirmation: boolean; reason: string },
) {
  return postCredentialAction(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/credentials`,
    accessToken,
    body,
    201,
  );
}

export function rotateCredential(
  tenantId: string,
  credentialId: string,
  accessToken: string,
  body: { confirmation: boolean; reason: string },
) {
  return postCredentialAction(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/credentials/${encodeURIComponent(credentialId)}/rotate`,
    accessToken,
    body,
    201,
  );
}

export function revokeCredential(
  tenantId: string,
  credentialId: string,
  accessToken: string,
  body: { confirmation: boolean; reason: string },
) {
  return postCredentialAction(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/credentials/${encodeURIComponent(credentialId)}/revoke`,
    accessToken,
    body,
    200,
  );
}
