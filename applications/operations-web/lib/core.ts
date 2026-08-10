import { config } from "./config";

export type CoreTenant = {
  id: string;
  name: string;
  defaultCurrency: string;
  defaultLocale: string;
  status: string;
};

export type CoreMerchant = {
  tenantId: string;
  merchantId: string;
  name: string;
  status: string;
  version: number;
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

export type CoreTenantConfiguration = {
  tenantId: string;
  version: number;
  allowedCurrencies: string[];
  defaultLocale: string;
  timezone: string;
  displaySettings: Record<string, unknown>;
  createdAt: string;
};

export type CoreOperationalContact = {
  tenantId: string;
  contactId: string;
  version: number;
  displayName: string;
  email: string;
  purpose: string;
  active: boolean;
  createdAt: string;
};

export type CoreMembershipRole = {
  assignmentId: string;
  role: string;
  scopeMode: string;
  merchantIds: string[];
};

export type CoreMembershipInvitation = {
  invitationId: string;
  intendedEmail: string;
  status: string;
  expiresAt: string;
};

export type CoreMembership = {
  tenantId: string;
  membershipId: string;
  status: string;
  version: number;
  initial: boolean;
  identityLinked: boolean;
  roleAssignments: CoreMembershipRole[];
  invitation: CoreMembershipInvitation | null;
};

export type CoreSupportSession = {
  supportSessionId: string;
  tenantId: string;
  startedAt: string;
  expiresAt: string;
  permission: "support:tenant-read";
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

export type CoreInvitationRevocationResult = {
  tenantId: string;
  membershipId: string;
  invitationId: string;
  membershipStatus: string;
  invitationStatus: string;
  membershipVersion: number;
};

export type CoreTenantConfigurationUpdateResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CoreTenantConfiguration };

export type CoreOperationalContactUpdateResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CoreOperationalContact };

export type CoreSupportSessionStartResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CoreSupportSession };

export type CoreReadOptions = {
  supportSessionId?: string;
};

function readHeaders(accessToken: string, options: CoreReadOptions = {}) {
  return {
    authorization: `Bearer ${accessToken}`,
    ...(options.supportSessionId
      ? { "x-support-session-id": options.supportSessionId }
      : {}),
  };
}

export async function getTenant(
  tenantId: string,
  accessToken: string,
  options: CoreReadOptions = {},
) {
  const response = await fetch(`${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}`, {
    headers: readHeaders(accessToken, options),
    cache: "no-store",
  });
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, tenant: (await response.json()) as CoreTenant };
}

export async function getMerchants(
  tenantId: string,
  accessToken: string,
  options: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/merchants`,
    {
      headers: readHeaders(accessToken, options),
      cache: "no-store",
    },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, merchants: await response.json() as CoreMerchant[] };
}

export async function getMemberships(
  tenantId: string,
  accessToken: string,
  options: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/memberships`,
    {
      headers: readHeaders(accessToken, options),
      cache: "no-store",
    },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, memberships: await response.json() as CoreMembership[] };
}

export async function startSupportSession(
  accessToken: string,
  body: { tenantId: string; confirmation: true; reason: string },
): Promise<CoreSupportSessionStartResponse> {
  const response = await fetch(`${config.coreBaseUrl}/api/v1/platform/support-sessions`, {
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
      const problem = await response.json() as { type?: unknown; code?: unknown };
      if (typeof problem.type === "string") code = problem.type;
      if (typeof problem.code === "string") code = problem.code;
    } catch {
      // The BFF maps an unparseable Core response to a generic action error.
    }
    return { kind: "error", status: response.status, code };
  }
  return { kind: "ok", result: await response.json() as CoreSupportSession };
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

export async function getTenantConfiguration(tenantId: string, accessToken: string) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/configuration`,
    {
      headers: { authorization: `Bearer ${accessToken}` },
      cache: "no-store",
    },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403) return { kind: "unavailable" as const };
  if (response.status === 404) return { kind: "missing" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, configuration: (await response.json()) as CoreTenantConfiguration };
}

export async function updateTenantConfiguration(
  tenantId: string,
  accessToken: string,
  body: {
    allowedCurrencies: string[];
    defaultLocale: string;
    timezone: string;
    displaySettings: Record<string, unknown>;
    confirmation: true;
    reason: string;
  },
): Promise<CoreTenantConfigurationUpdateResponse> {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/configuration`,
    {
      method: "PUT",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
      cache: "no-store",
    },
  );
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
  if (response.status !== 200) {
    return { kind: "error", status: 502, code: "unexpected_core_response" };
  }
  return { kind: "ok", result: await response.json() as CoreTenantConfiguration };
}

export async function getOperationalContacts(tenantId: string, accessToken: string) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/operational-contacts`,
    {
      headers: { authorization: `Bearer ${accessToken}` },
      cache: "no-store",
    },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, contacts: await response.json() as CoreOperationalContact[] };
}

export async function updateOperationalContact(
  tenantId: string,
  contactId: string,
  accessToken: string,
  body: {
    displayName: string;
    email: string;
    purpose: string;
    active: boolean;
    confirmation: true;
    reason: string;
  },
): Promise<CoreOperationalContactUpdateResponse> {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/operational-contacts/${encodeURIComponent(contactId)}`,
    {
      method: "PUT",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify(body),
      cache: "no-store",
    },
  );
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
  if (response.status !== 200) {
    return { kind: "error", status: 502, code: "unexpected_core_response" };
  }
  return { kind: "ok", result: await response.json() as CoreOperationalContact };
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

export type CoreInvitationRevocationResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CoreInvitationRevocationResult };

export function revokeInvitation(
  tenantId: string,
  membershipId: string,
  accessToken: string,
  body: { confirmation: boolean; reason: string },
): Promise<CoreInvitationRevocationResponse> {
  return postMembershipAction(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/memberships/${encodeURIComponent(membershipId)}/invitation/revoke`,
    accessToken,
    body,
  );
}

async function postMembershipAction(
  path: string,
  accessToken: string,
  body: Record<string, unknown>,
): Promise<CoreInvitationRevocationResponse> {
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
  if (response.status !== 200) {
    return { kind: "error", status: 502, code: "unexpected_core_response" };
  }
  return { kind: "ok", result: await response.json() as CoreInvitationRevocationResult };
}
