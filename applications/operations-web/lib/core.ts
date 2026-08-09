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
