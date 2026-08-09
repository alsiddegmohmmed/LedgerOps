import { updateTenantConfiguration } from "../../../../lib/core";
import {
  invalidTenantConfigurationRequest,
  mapTenantConfigurationResponse,
  readTenantConfigurationBody,
  requiredText,
  requireTenantConfigurationSession,
} from "../../../../lib/tenant-configuration-actions";

function validCurrencyList(value: unknown): value is string[] {
  return Array.isArray(value)
    && value.length > 0
    && value.length <= 20
    && value.every((currency) => typeof currency === "string" && /^[A-Z]{3}$/.test(currency));
}

function validDisplaySettings(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function PUT(request: Request) {
  const access = await requireTenantConfigurationSession(request);
  if ("response" in access) return access.response;

  const body = await readTenantConfigurationBody(request);
  const defaultLocale = requiredText(body?.defaultLocale, 35);
  const timezone = requiredText(body?.timezone, 100);
  const reason = requiredText(body?.reason, 512);
  if (
    !body
    || !validCurrencyList(body.allowedCurrencies)
    || !defaultLocale
    || !timezone
    || !validDisplaySettings(body.displaySettings)
    || body.confirmation !== true
    || !reason
  ) {
    return invalidTenantConfigurationRequest();
  }

  const result = await updateTenantConfiguration(
    access.session.selectedTenantId!,
    access.session.accessToken,
    {
      allowedCurrencies: body.allowedCurrencies,
      defaultLocale,
      timezone,
      displaySettings: body.displaySettings,
      confirmation: true,
      reason,
    },
  );
  return mapTenantConfigurationResponse(result, 200, "tenant_configuration_update_failed");
}
