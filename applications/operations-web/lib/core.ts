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

export type CoreSettlementBatch = {
  batchVersionId: string;
  familyId: string;
  tenantId: string;
  providerId: string;
  providerBatchReference: string;
  settlementPeriodStart: string;
  settlementPeriodEnd: string;
  rawFileSha256: string;
  byteSize: number;
  status: "RECEIVED" | "VALIDATING" | "READY" | "PROCESSING" | "COMPLETED" | "COMPLETED_WITH_DISCREPANCIES" | "FAILED";
  supersedesBatchVersionId: string | null;
  totalRows: number;
  validRows: number;
  invalidRows: number;
  structuralErrorCode: string | null;
  createdAt: string;
  updatedAt: string;
};

export type CoreSettlementValidationItem = {
  validationItemId: string;
  rowNumber: number;
  reasonCode: string;
  safeEvidence: Record<string, unknown>;
  createdAt: string;
};

export type CoreSettlementBatchPage = {
  items: CoreSettlementBatch[];
};

export type CoreReconciliationRun = {
  runId: string;
  tenantId: string;
  batchFamilyId: string;
  batchVersionId: string;
  snapshotId: string;
  runNumber: number;
  rulesVersion: string;
  sourceCutoff: string;
  status: string;
  matchedCount: number;
  unmatchedCount: number;
  discrepancyCount: number;
  createdAt: string;
  startedAt: string | null;
  terminalAt: string | null;
  failureReason: string | null;
};

export type CoreReconciliationResult = {
  resultId: string;
  occurrenceId: string | null;
  canonicalRecordVersionId: string | null;
  subjectType: string | null;
  subjectId: string | null;
  resultStatus: string;
  discrepancyCategory: string | null;
  providerValuesJson: string;
  internalValuesJson: string;
  createdAt: string;
};

export type CoreReconciliationCurrentRun = {
  tenantId: string;
  batchFamilyId: string;
  runId: string;
  promotedAt: string;
};

export type CoreReconciliationPosting = {
  settlementPostingId: string;
  tenantId: string;
  runId: string;
  canonicalRecordVersionId: string;
  occurrenceId: string;
  subjectType: string;
  subjectId: string;
  templateVersion: string;
  amount: number | string;
  currency: string;
  instructionHash: string;
  applicationStatus: string;
  ledgerTransactionId: string | null;
  createdAt: string;
  postedAt: string | null;
};

export type CoreReconciliationStatusHistory = {
  statusId: string;
  tenantId: string;
  subjectType: string;
  subjectId: string;
  runId: string | null;
  status: string;
  occurredAt: string;
};

export type CoreReconciliationPostingOutcome = {
  settlementPostingId: string;
  subjectType: string;
  status: string;
  ledgerTransactionId: string | null;
};

export type CorePaymentSearchItem = {
  paymentId: string;
  tenantId: string;
  merchantReference: string;
  customerId: string;
  amount: number | string;
  currency: string;
  state: string;
  createdAt: string;
  updatedAt: string;
  riskDecision: string | null;
  reconciliationStatus: string | null;
};

export type CorePaymentPage = {
  items: CorePaymentSearchItem[];
  nextCursor: string | null;
};

export type CorePaymentAttempt = {
  attemptId: string;
  sequence: number;
  providerId: string;
  providerIdempotencyKey: string;
  initiatedAt: string;
};

export type CorePaymentNote = {
  noteId: string;
  tenantId: string;
  paymentId: string;
  merchantId: string;
  authorIssuer: string;
  authorSubject: string;
  content: string;
  createdAt: string;
};

export type CoreRiskSnapshot = {
  evaluationId: string;
  profileId: string;
  profileVersion: number;
  finalScore: number;
  decision: string;
  evaluatedAt: string;
} | null;

export type CoreRiskReview = {
  reviewId: string;
  tenantId: string;
  paymentId: string;
  merchantId: string | null;
  evaluationId: string;
  status: "UNASSIGNED" | "ASSIGNED" | "DECIDED" | "ESCALATED";
  assignedAnalystId: string | null;
  priority: number;
  slaVersion: number;
  createdAt: string;
  dueAt: string;
  decision: "APPROVE" | "REJECT" | "ESCALATE" | null;
  decisionReason: string | null;
  caseId: string | null;
  decidedAt: string | null;
  version: number;
};

export type CoreCaseHistoryEntry = {
  sequence: number;
  eventType: string;
  fromStatus: string | null;
  toStatus: string | null;
  actorId: string;
  reason: string;
  occurredAt: string;
};

export type CoreCaseNote = {
  noteId: string;
  authorId: string;
  text: string;
  createdAt: string;
};

export type CoreCase = {
  caseId: string;
  tenantId: string;
  sourceCategory: "RISK_REVIEW" | "RECONCILIATION_DISCREPANCY";
  sourceId: string;
  relatedPaymentId: string | null;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
  createdAt: string;
  dueAt: string;
  status: "OPEN" | "INVESTIGATING" | "AWAITING_INFORMATION" | "RESOLVED" | "CLOSED" | "REOPENED";
  ownerId: string | null;
  resolution: string | null;
  resolutionNote: string | null;
  history: CoreCaseHistoryEntry[];
  notes: CoreCaseNote[];
};

export type CoreProviderEvidence = {
  evidenceId: string;
  tenantId: string;
  paymentId: string;
  attemptId: string;
  providerId: string;
  providerIdempotencyKey: string;
  providerResultId: string;
  providerReference: string | null;
  category: string;
  retryDisposition: string;
  providerTransactionFound: boolean;
  noAcceptanceProven: boolean;
  evidenceOrigin: string;
  observedAt: string;
};

export type CoreProviderWorkOperation = {
  workId: string;
  tenantId: string;
  paymentId: string;
  operationType: "PAYMENT" | "REVERSAL";
  operationId: string;
  attemptId: string;
  attemptSequence: number;
  workType: string;
  status: string;
  providerId: string;
  providerIdempotencyKey: string;
  dueAt: string;
  executionCount: number;
  transportRetryCount: number;
  lastErrorCode: string | null;
  scenarioProfileId: string | null;
  scenarioProfileVersion: number | null;
  createdAt: string;
  updatedAt: string;
};

export type CoreProviderInteractionOperation = {
  interactionId: string;
  tenantId: string;
  workId: string | null;
  webhookEventId: string | null;
  paymentId: string;
  operationType: "PAYMENT" | "REVERSAL";
  operationId: string;
  attemptId: string;
  providerId: string;
  workType: string;
  requestId: string;
  httpStatus: number | null;
  communicationOutcome: string;
  latencyMillis: number;
  safeErrorCode: string | null;
  startedAt: string;
  completedAt: string;
};

export type CoreProviderRecoveryOperation = {
  evidenceId: string;
  workId: string | null;
  attemptId: string;
  operationType: "PAYMENT" | "REVERSAL";
  operationId: string;
  retryRequestId: string | null;
  workStatus: string | null;
  resultCategory: string;
  retryDisposition: string;
  providerTransactionFound: boolean;
  noAcceptanceProven: boolean;
  dueAt: string | null;
  requestedAt: string | null;
  observedAt: string;
};

export type CoreProviderWebhookOperation = {
  eventId: string;
  providerEventId: string;
  paymentId: string;
  attemptId: string;
  resultCategory: string;
  status: string;
  receiptCount: number;
  receivedAt: string;
  updatedAt: string;
};

export type CoreProviderOperations = {
  tenantId: string;
  paymentId: string;
  work: CoreProviderWorkOperation[];
  interactions: CoreProviderInteractionOperation[];
  recovery: CoreProviderRecoveryOperation[];
  webhooks: CoreProviderWebhookOperation[];
} | null;

export type CoreProviderHealth = {
  evaluationId: string;
  providerId: string;
  policyId: string;
  policyVersion: number;
  healthVersion: number;
  state: "UNKNOWN" | "HEALTHY" | "DEGRADED" | "UNAVAILABLE";
  completedCalls: number;
  successfulCommunications: number;
  timeoutCount: number;
  systemErrorCount: number;
  p95LatencyMillis: number;
  circuitState: string;
  windowStartedAt: string;
  windowEndedAt: string;
  evaluatedAt: string;
};

export type CoreProviderScenarioAssignment = {
  assignmentId: string;
  scope: "GLOBAL" | "TENANT" | "PAYMENT";
  tenantId: string | null;
  paymentId: string | null;
  profileId: string;
  profileVersion: number;
  active: boolean;
  createdAt: string;
};

export type CoreProviderScenarioProfile = {
  profileId: string;
  version: number;
  submissionOutcome: string;
  webhookMode: string;
  settlementMode: string;
  delayMillis: number;
  fixtureId: string | null;
  parameters: Record<string, string>;
  createdAt: string;
};

export type CoreWebhookEndpoint = {
  endpointId: string;
  tenantId: string;
  merchantId: string;
  label: string;
  endpointUrl: string;
  status: "ACTIVE" | "REVOKED";
  keyVersion: string;
  allowedEventTypes: string[];
  createdAt: string;
  rotatedAt: string | null;
  revokedAt: string | null;
};

export type CoreWebhookDelivery = {
  deliveryId: string;
  eventId: string;
  tenantId: string;
  merchantId: string;
  endpointId: string;
  endpointStatus: "ACTIVE" | "REVOKED";
  eventType: string;
  status: string;
  attemptCount: number;
  nextAttemptAt: string | null;
  createdAt: string;
  updatedAt: string;
  lastHttpStatus: number | null;
  lastOutcome: string | null;
  lastSafeSummary: string | null;
};

export type CoreWebhookSecretResult = {
  endpoint: CoreWebhookEndpoint;
  plaintextSecret: string;
};

export type CoreRiskRuleConfiguration = {
  currency: string;
  amountThreshold: number | string | null;
  scoreContribution: number;
  enabled: boolean;
};

export type CoreRiskConfiguration = {
  tenantId: string;
  profileId: string;
  version: number;
  reviewThreshold: number;
  rejectThreshold: number;
  active: boolean;
  createdAt: string;
  rules: CoreRiskRuleConfiguration[];
};

export type CoreLedgerPostingEntry = {
  accountId: string;
  accountCode: string;
  direction: string;
  amount: number | string;
  currency: string;
};

export type CoreLedgerPosting = {
  transactionId: string;
  tenantId: string;
  sourceType: string;
  sourceId: string;
  currency: string;
  totalDebits: number | string;
  totalCredits: number | string;
  entries: CoreLedgerPostingEntry[];
  compensatesTransactionId: string | null;
} | null;

export type CorePaymentTimelineEntry = {
  sourceMessageId: string;
  tenantId: string;
  paymentId: string;
  merchantId: string;
  sourceModule: string;
  sourceType: string;
  sourceId: string;
  occurredAt: string;
  actorSource: string;
  outcome: string;
  reasonCode: string;
  correlationId: string;
  displayText: string;
};

export type CorePaymentDetail = {
  payment: {
    paymentId: string;
    tenantId: string;
    merchantId: string;
    customerId: string;
    amount: number | string;
    currency: string;
    paymentMethodCategory: string;
    state: string;
    createdAt: string;
    updatedAt: string;
    attempts: CorePaymentAttempt[];
    notes: CorePaymentNote[];
  };
  risk: CoreRiskSnapshot;
  providerEvidence: CoreProviderEvidence[];
  ledgerPosting: CoreLedgerPosting;
  reconciliationStatus: string;
  timeline: CorePaymentTimelineEntry[];
  notes: CorePaymentNote[];
  attempts: CorePaymentAttempt[];
  providerOperations: CoreProviderOperations;
  reversal: CoreReversalDetails | null;
};

export type CoreReversalDetails = {
  reversalId: string;
  tenantId: string;
  paymentId: string;
  merchantId: string;
  amount: number | string;
  currency: string;
  status: "REQUESTED" | "PROCESSING" | "FAILED" | "COMPLETED";
  requestedBy: string;
  requestReason: string;
  requestedAt: string;
  processingAt: string | null;
  failedAt: string | null;
  completedAt: string | null;
  failureCategory: string | null;
  version: number;
};

export type CoreLedgerBalance = {
  accountId: string;
  currency: string;
  totalDebits: number | string;
  totalCredits: number | string;
  asOfExclusive: string;
};

export type CoreLedgerStatementEntry = {
  transactionId: string;
  entryIndex: number;
  sourceType: string;
  sourceId: string;
  postedAt: string;
  direction: string;
  amount: number | string;
  currency: string;
};

export type CoreLedgerStatement = {
  accountId: string;
  currency: string;
  fromInclusive: string;
  toExclusive: string;
  totalDebits: number | string;
  totalCredits: number | string;
  totalEntries: number;
  offset: number;
  limit: number;
  entries: CoreLedgerStatementEntry[];
};

export type CoreAuditItem = {
  auditId: string;
  actorIssuer: string;
  actorSubject: string;
  principalType: string;
  tenantId: string;
  action: string;
  entity: string;
  entityId: string;
  correlationId: string;
  reason: string;
  details: string;
  occurredAt: string;
};

export type CoreAuditPage = {
  items: CoreAuditItem[];
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

export type CoreActionResponse<T> =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: T };

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

export async function getSettlementBatches(
  tenantId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/settlement-batches`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, page: await response.json() as CoreSettlementBatchPage };
}

export async function getSettlementBatch(
  tenantId: string,
  batchVersionId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/settlement-batches/${encodeURIComponent(batchVersionId)}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, batch: await response.json() as CoreSettlementBatch };
}

export async function getSettlementValidationItems(
  tenantId: string,
  batchVersionId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/settlement-batches/${encodeURIComponent(batchVersionId)}/validation-items`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, items: await response.json() as CoreSettlementValidationItem[] };
}

export async function getReconciliationRuns(
  tenantId: string,
  accessToken: string,
  options: { batchFamilyId?: string; limit?: number } = {},
  readOptions: CoreReadOptions = {},
) {
  const params = new URLSearchParams();
  if (options.batchFamilyId) params.set("batchFamilyId", options.batchFamilyId);
  if (options.limit !== undefined) params.set("limit", String(options.limit));
  const query = params.toString();
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs${query ? `?${query}` : ""}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, runs: await response.json() as CoreReconciliationRun[] };
}

export async function getReconciliationRun(
  tenantId: string,
  runId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs/${encodeURIComponent(runId)}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, run: await response.json() as CoreReconciliationRun };
}

export async function getCurrentReconciliationRun(
  tenantId: string,
  batchFamilyId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs/current?batchFamilyId=${encodeURIComponent(batchFamilyId)}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, current: await response.json() as CoreReconciliationCurrentRun };
}

export async function getReconciliationResults(
  tenantId: string,
  runId: string,
  accessToken: string,
  options: { status?: string; category?: string; limit?: number; offset?: number } = {},
  readOptions: CoreReadOptions = {},
) {
  const params = new URLSearchParams();
  if (options.status) params.set("status", options.status);
  if (options.category) params.set("category", options.category);
  if (options.limit !== undefined) params.set("limit", String(options.limit));
  if (options.offset !== undefined) params.set("offset", String(options.offset));
  const query = params.toString();
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs/${encodeURIComponent(runId)}/results${query ? `?${query}` : ""}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, results: await response.json() as CoreReconciliationResult[] };
}

export async function getReconciliationPostings(
  tenantId: string,
  runId: string,
  accessToken: string,
  options: { limit?: number; offset?: number } = {},
  readOptions: CoreReadOptions = {},
) {
  const params = new URLSearchParams();
  if (options.limit !== undefined) params.set("limit", String(options.limit));
  if (options.offset !== undefined) params.set("offset", String(options.offset));
  const query = params.toString();
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs/${encodeURIComponent(runId)}/postings${query ? `?${query}` : ""}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, postings: await response.json() as CoreReconciliationPosting[] };
}

export async function uploadSettlementBatch(
  tenantId: string,
  accessToken: string,
  form: FormData,
): Promise<CoreActionResponse<CoreSettlementBatch>> {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/settlement-batches`,
    {
      method: "POST",
      headers: { authorization: `Bearer ${accessToken}` },
      body: form,
      cache: "no-store",
    },
  );
  if (response.status === 401) return { kind: "unauthenticated" };
  if (!response.ok) {
    let code: string | undefined;
    try {
      const problem = await response.json() as { code?: unknown; type?: unknown };
      if (typeof problem.code === "string") code = problem.code;
      if (typeof problem.type === "string") code = problem.type;
    } catch {
      // The BFF maps an unparseable Core response to a generic action failure.
    }
    return { kind: "error", status: response.status, code };
  }
  if (response.status !== 201) return { kind: "error", status: 502, code: "unexpected_core_response" };
  return { kind: "ok", result: await response.json() as CoreSettlementBatch };
}

export function validateSettlementBatch(
  tenantId: string,
  batchVersionId: string,
  accessToken: string,
): Promise<CoreActionResponse<CoreSettlementBatch>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/settlement-batches/${encodeURIComponent(batchVersionId)}/validate`,
    accessToken,
    undefined,
    200,
  );
}

export function processSettlementBatch(
  tenantId: string,
  batchVersionId: string,
  accessToken: string,
): Promise<CoreActionResponse<CoreSettlementBatch>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/settlement-batches/${encodeURIComponent(batchVersionId)}/process`,
    accessToken,
    { confirmation: true },
    200,
  );
}

export function executeReconciliationRun(
  tenantId: string,
  accessToken: string,
  body: { batchVersionId: string; rulesVersion: string; sourceCutoff: string; confirmation: true },
): Promise<CoreActionResponse<CoreReconciliationRun>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs`,
    accessToken,
    body,
    201,
  );
}

export function promoteReconciliationRun(
  tenantId: string,
  runId: string,
  accessToken: string,
  body: { batchFamilyId: string; confirmation: true; reason: string },
): Promise<CoreActionResponse<CoreReconciliationCurrentRun>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs/${encodeURIComponent(runId)}/promote`,
    accessToken,
    body,
    200,
  );
}

export function prepareReconciliationPosting(
  tenantId: string,
  runId: string,
  accessToken: string,
  body: { batchFamilyId: string; confirmation: true; reason: string },
): Promise<CoreActionResponse<CoreReconciliationPostingOutcome[]>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs/${encodeURIComponent(runId)}/postings/prepare`,
    accessToken,
    body,
    200,
  );
}

export function postReconciliation(
  tenantId: string,
  runId: string,
  accessToken: string,
  body: { batchFamilyId: string; confirmation: true; reason: string },
): Promise<CoreActionResponse<CoreReconciliationPostingOutcome[]>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/reconciliation-runs/${encodeURIComponent(runId)}/postings`,
    accessToken,
    body,
    200,
  );
}

export async function getPaymentPage(
  tenantId: string,
  accessToken: string,
  options: {
    platformId?: string;
    merchantReference?: string;
    providerId?: string;
    customerId?: string;
    from?: string;
    to?: string;
    minAmount?: string;
    maxAmount?: string;
    state?: string;
    riskDecision?: string;
    reconciliationStatus?: string;
    limit?: number;
    cursor?: string;
  } = {},
  readOptions: CoreReadOptions = {},
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(options)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  const query = params.toString();
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/payments${query ? `?${query}` : ""}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, page: await response.json() as CorePaymentPage };
}

export async function getRiskReviewQueue(
  tenantId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
): Promise<
  | { kind: "unauthenticated" }
  | { kind: "unavailable" }
  | { kind: "error" }
  | { kind: "ok"; reviews: CoreRiskReview[] }
> {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/risk-reviews`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" };
  if (!response.ok) return { kind: "error" };
  return { kind: "ok", reviews: await response.json() as CoreRiskReview[] };
}

export async function getCaseQueue(
  tenantId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
): Promise<
  | { kind: "unauthenticated" }
  | { kind: "unavailable" }
  | { kind: "error" }
  | { kind: "ok"; cases: CoreCase[] }
> {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/cases`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" };
  if (!response.ok) return { kind: "error" };
  return { kind: "ok", cases: await response.json() as CoreCase[] };
}

async function postCoreAction<T>(
  path: string,
  accessToken: string,
  body: Record<string, unknown>,
): Promise<CoreActionResponse<T>> {
  const response = await fetch(`${config.coreBaseUrl}${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  if (response.status === 401) return { kind: "unauthenticated" };
  if (!response.ok) {
    let code: string | undefined;
    try {
      const problem = await response.json() as { code?: unknown; type?: unknown };
      if (typeof problem.code === "string") code = problem.code;
      if (typeof problem.type === "string") code = problem.type;
    } catch {
      // Treat an unparseable Core response as a generic action failure.
    }
    return { kind: "error", status: response.status, code };
  }
  return { kind: "ok", result: await response.json() as T };
}

export function assignRiskReview(
  tenantId: string,
  reviewId: string,
  accessToken: string,
  body: { analystId: string; priority: number; reason: string },
) {
  return postCoreAction<CoreRiskReview>(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/risk-reviews/${encodeURIComponent(reviewId)}/assignment`,
    accessToken,
    body,
  );
}

export function decideRiskReview(
  tenantId: string,
  reviewId: string,
  accessToken: string,
  body: { decision: "APPROVE" | "REJECT" | "ESCALATE"; reason: string },
) {
  return postCoreAction<unknown>(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/risk-reviews/${encodeURIComponent(reviewId)}/decisions`,
    accessToken,
    body,
  );
}

export function assignCase(
  tenantId: string,
  caseId: string,
  accessToken: string,
  body: { ownerId: string; reason: string },
) {
  return postCoreAction<CoreCase>(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/cases/${encodeURIComponent(caseId)}/assignment`,
    accessToken,
    body,
  );
}

export function transitionCase(
  tenantId: string,
  caseId: string,
  accessToken: string,
  body: { target: CoreCase["status"]; reason: string },
) {
  return postCoreAction<CoreCase>(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/cases/${encodeURIComponent(caseId)}/transitions`,
    accessToken,
    body,
  );
}

export function addCaseNote(
  tenantId: string,
  caseId: string,
  accessToken: string,
  body: { note: string },
) {
  return postCoreAction<CoreCase>(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/cases/${encodeURIComponent(caseId)}/notes`,
    accessToken,
    body,
  );
}

export function resolveCase(
  tenantId: string,
  caseId: string,
  accessToken: string,
  body: { resolution: string; note: string },
) {
  return postCoreAction<CoreCase>(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/cases/${encodeURIComponent(caseId)}/resolution`,
    accessToken,
    body,
  );
}

export function closeCase(
  tenantId: string,
  caseId: string,
  accessToken: string,
  body: { reason: string },
) {
  return postCoreAction<CoreCase>(
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/cases/${encodeURIComponent(caseId)}/close`,
    accessToken,
    body,
  );
}

export async function getPaymentDetail(
  tenantId: string,
  paymentId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/payments/${encodeURIComponent(paymentId)}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, detail: await response.json() as CorePaymentDetail };
}

export type CorePaymentNoteActionResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CorePaymentNote };

export async function addPaymentNote(
  tenantId: string,
  paymentId: string,
  accessToken: string,
  content: string,
): Promise<CorePaymentNoteActionResponse> {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/payments/${encodeURIComponent(paymentId)}/notes`,
    {
      method: "POST",
      headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
      body: JSON.stringify({ content }),
      cache: "no-store",
    },
  );
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
  if (response.status !== 201) return { kind: "error", status: 502, code: "unexpected_core_response" };
  return { kind: "ok", result: await response.json() as CorePaymentNote };
}

export async function getLedgerBalance(
  tenantId: string,
  accountId: string,
  accessToken: string,
  options: { asOf?: string } = {},
  readOptions: CoreReadOptions = {},
) {
  const params = new URLSearchParams();
  if (options.asOf) params.set("asOf", options.asOf);
  const query = params.toString();
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/ledger/accounts/${encodeURIComponent(accountId)}/balance${query ? `?${query}` : ""}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, balance: await response.json() as CoreLedgerBalance };
}

export async function getLedgerStatement(
  tenantId: string,
  accountId: string,
  accessToken: string,
  options: { from: string; to: string; offset?: number; limit?: number },
  readOptions: CoreReadOptions = {},
) {
  const params = new URLSearchParams({ from: options.from, to: options.to });
  if (options.offset !== undefined) params.set("offset", String(options.offset));
  if (options.limit !== undefined) params.set("limit", String(options.limit));
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/ledger/accounts/${encodeURIComponent(accountId)}/statement?${params.toString()}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, statement: await response.json() as CoreLedgerStatement };
}

export async function getAuditPage(
  tenantId: string,
  accessToken: string,
  options: {
    actorIssuer?: string;
    actorSubject?: string;
    action?: string;
    entity?: string;
    entityId?: string;
    from?: string;
    to?: string;
    result?: string;
    correlationId?: string;
    limit?: number;
    cursor?: string;
  } = {},
  readOptions: CoreReadOptions = {},
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(options)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  const query = params.toString();
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/audit${query ? `?${query}` : ""}`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, page: await response.json() as CoreAuditPage };
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

export async function getProviderHealth(
  tenantId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/provider/health`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, health: await response.json() as CoreProviderHealth };
}

export async function getRiskConfiguration(tenantId: string, accessToken: string) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/risk/configuration`,
    { headers: { authorization: `Bearer ${accessToken}` }, cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, configuration: await response.json() as CoreRiskConfiguration };
}

export async function getRiskConfigurationHistory(tenantId: string, accessToken: string) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/risk/configuration/history`,
    { headers: { authorization: `Bearer ${accessToken}` }, cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, history: await response.json() as CoreRiskConfiguration[] };
}

export function updateRiskConfiguration(
  tenantId: string,
  accessToken: string,
  body: {
    reviewThreshold: number;
    rejectThreshold: number;
    rules: CoreRiskRuleConfiguration[];
    expectedVersion: number | null;
    confirmation: true;
    reason: string;
  },
): Promise<CoreActionResponse<CoreRiskConfiguration>> {
  return requestCoreAction(
    "PUT",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/risk/configuration`,
    accessToken,
    body,
    200,
  );
}

export async function getProviderHealthHistory(
  tenantId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/provider/health/history`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, history: await response.json() as CoreProviderHealth[] };
}

export async function getProviderScenarioAssignments(accessToken: string) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/platform/provider/scenarios/assignments`,
    { headers: { authorization: `Bearer ${accessToken}` }, cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  const body = await response.json() as { assignments: CoreProviderScenarioAssignment[] };
  return { kind: "ok" as const, assignments: body.assignments };
}

export type CoreProviderScenarioActionResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CoreProviderScenarioProfile | CoreProviderScenarioAssignment };

export function createProviderScenarioProfile(
  accessToken: string,
  body: {
    profileId: string | null;
    expectedPreviousVersion: number | null;
    submissionOutcome: string;
    webhookMode: string;
    settlementMode: string;
    delayMillis: number;
    fixtureId: string | null;
    parameters: Record<string, string>;
  },
): Promise<CoreProviderScenarioActionResponse> {
  return requestCoreAction(
    "POST",
    "/api/v1/platform/provider/scenarios/profiles",
    accessToken,
    body,
    200,
  ) as Promise<CoreProviderScenarioActionResponse>;
}

export function assignProviderScenario(
  accessToken: string,
  body: {
    scope: string;
    tenantId: string | null;
    paymentId: string | null;
    profileId: string;
    profileVersion: number;
  },
): Promise<CoreProviderScenarioActionResponse> {
  return requestCoreAction(
    "POST",
    "/api/v1/platform/provider/scenarios/assignments",
    accessToken,
    body,
    200,
  ) as Promise<CoreProviderScenarioActionResponse>;
}

export type CorePaymentRetryNowResult = {
  paymentId: string;
  providerWorkId: string;
  previousDueAt: string;
  dueAt: string;
  status: "ACCELERATED";
};

export type CoreReversalRetryResult = {
  reversalId: string;
  paymentId: string;
  attemptId: string;
  attemptSequence: number;
  status: "PROCESSING";
  replay: boolean;
};

export function retryPaymentNow(
  tenantId: string,
  paymentId: string,
  accessToken: string,
  body: { confirmation: true; reason: string },
): Promise<CoreActionResponse<CorePaymentRetryNowResult>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/payments/${encodeURIComponent(paymentId)}/retry`,
    accessToken,
    body,
    200,
  );
}

export function requestReversal(
  tenantId: string,
  paymentId: string,
  accessToken: string,
  body: { confirmation: true; reason: string },
): Promise<CoreActionResponse<CoreReversalDetails>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/reversals`,
    accessToken,
    { paymentId, ...body },
    201,
  );
}

export function retryReversal(
  tenantId: string,
  reversalId: string,
  accessToken: string,
  body: {
    paymentId: string;
    previousAttemptId: string;
    providerEvidenceId: string;
    confirmation: true;
    reason: string;
  },
): Promise<CoreActionResponse<CoreReversalRetryResult>> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/reversals/${encodeURIComponent(reversalId)}/retry`,
    accessToken,
    body,
    200,
  );
}

export async function getWebhookEndpoints(
  tenantId: string,
  merchantId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/merchants/${encodeURIComponent(merchantId)}/webhooks`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, endpoints: await response.json() as CoreWebhookEndpoint[] };
}

export async function getWebhookDeliveries(
  tenantId: string,
  merchantId: string,
  endpointId: string,
  accessToken: string,
  readOptions: CoreReadOptions = {},
) {
  const response = await fetch(
    `${config.coreBaseUrl}/api/v1/tenants/${encodeURIComponent(tenantId)}/merchants/${encodeURIComponent(merchantId)}/webhooks/${encodeURIComponent(endpointId)}/deliveries`,
    { headers: readHeaders(accessToken, readOptions), cache: "no-store" },
  );
  if (response.status === 401) return { kind: "unauthenticated" as const };
  if (response.status === 403 || response.status === 404) return { kind: "unavailable" as const };
  if (!response.ok) return { kind: "error" as const };
  return { kind: "ok" as const, deliveries: await response.json() as CoreWebhookDelivery[] };
}

export type CoreWebhookActionResponse =
  | { kind: "unauthenticated" }
  | { kind: "error"; status: number; code?: string }
  | { kind: "ok"; result: CoreWebhookSecretResult | CoreWebhookEndpoint | CoreWebhookDelivery };

export function createWebhookEndpoint(
  tenantId: string,
  merchantId: string,
  accessToken: string,
  body: { label: string; endpointUrl: string; allowedEventTypes: string[] },
): Promise<CoreWebhookActionResponse> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/merchants/${encodeURIComponent(merchantId)}/webhooks`,
    accessToken,
    body,
    201,
  ) as Promise<CoreWebhookActionResponse>;
}

export function rotateWebhookEndpoint(
  tenantId: string,
  merchantId: string,
  endpointId: string,
  accessToken: string,
): Promise<CoreWebhookActionResponse> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/merchants/${encodeURIComponent(merchantId)}/webhooks/${encodeURIComponent(endpointId)}/rotate`,
    accessToken,
    undefined,
    200,
  ) as Promise<CoreWebhookActionResponse>;
}

export function revokeWebhookEndpoint(
  tenantId: string,
  merchantId: string,
  endpointId: string,
  accessToken: string,
): Promise<CoreWebhookActionResponse> {
  return requestCoreAction(
    "DELETE",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/merchants/${encodeURIComponent(merchantId)}/webhooks/${encodeURIComponent(endpointId)}`,
    accessToken,
    undefined,
    200,
  ) as Promise<CoreWebhookActionResponse>;
}

export function triggerWebhookTest(
  tenantId: string,
  merchantId: string,
  endpointId: string,
  accessToken: string,
  body: { eventType: string; payload: Record<string, unknown> },
): Promise<CoreWebhookActionResponse> {
  return requestCoreAction(
    "POST",
    `/api/v1/tenants/${encodeURIComponent(tenantId)}/merchants/${encodeURIComponent(merchantId)}/webhooks/${encodeURIComponent(endpointId)}/test-events`,
    accessToken,
    body,
    202,
  ) as Promise<CoreWebhookActionResponse>;
}

async function requestCoreAction<T>(
  method: "POST" | "PUT" | "DELETE",
  path: string,
  accessToken: string,
  body: Record<string, unknown> | undefined,
  expectedStatus: number,
): Promise<CoreActionResponse<T>> {
  const response = await fetch(`${config.coreBaseUrl}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${accessToken}`,
      ...(body ? { "content-type": "application/json" } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    cache: "no-store",
  });
  if (response.status === 401) return { kind: "unauthenticated" };
  if (!response.ok) {
    let code: string | undefined;
    try {
      const problem = await response.json() as { code?: unknown; type?: unknown };
      if (typeof problem.code === "string") code = problem.code;
      if (typeof problem.type === "string") code = problem.type;
    } catch {
      // The BFF maps an unparseable Core response to a generic action error.
    }
    return { kind: "error", status: response.status, code };
  }
  if (response.status !== expectedStatus) {
    return { kind: "error", status: 502, code: "unexpected_core_response" };
  }
  return { kind: "ok", result: await response.json() as T };
}
