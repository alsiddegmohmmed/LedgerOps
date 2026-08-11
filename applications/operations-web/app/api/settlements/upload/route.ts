import { uploadSettlementBatch } from "../../../../lib/core";
import {
  invalidSettlementActionRequest,
  mapCredentialActionResponse,
  requireCredentialActionSession,
} from "../../../../lib/settlement-actions";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

function text(value: FormDataEntryValue | null, max: number) {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  return normalized && normalized.length <= max ? normalized : null;
}

function uuid(value: FormDataEntryValue | null) {
  return typeof value === "string" && UUID.test(value) ? value : null;
}

function date(value: FormDataEntryValue | null) {
  return typeof value === "string" && DATE.test(value) ? value : null;
}

export async function POST(request: Request) {
  const access = await requireCredentialActionSession(request);
  if ("response" in access) return access.response;

  let source: FormData;
  try {
    source = await request.formData();
  } catch {
    return invalidSettlementActionRequest();
  }
  const file = source.get("file");
  const providerBatchReference = text(source.get("providerBatchReference"), 100);
  const settlementPeriodStart = date(source.get("settlementPeriodStart"));
  const settlementPeriodEnd = date(source.get("settlementPeriodEnd"));
  const supersedesBatchVersionId = source.get("supersedesBatchVersionId");
  if (
    !(file instanceof File)
    || file.size === 0
    || file.size > 50 * 1024 * 1024
    || !providerBatchReference
    || !settlementPeriodStart
    || !settlementPeriodEnd
    || (supersedesBatchVersionId !== null && !uuid(supersedesBatchVersionId))
  ) {
    return invalidSettlementActionRequest();
  }

  const form = new FormData();
  form.append("providerBatchReference", providerBatchReference);
  form.append("settlementPeriodStart", settlementPeriodStart);
  form.append("settlementPeriodEnd", settlementPeriodEnd);
  if (supersedesBatchVersionId) form.append("supersedesBatchVersionId", supersedesBatchVersionId);
  form.append("file", file, file.name);

  const result = await uploadSettlementBatch(
    access.session.selectedTenantId!,
    access.session.accessToken,
    form,
  );
  return mapCredentialActionResponse(result, 201, "settlement_upload_failed");
}
