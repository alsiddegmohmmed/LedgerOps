import { expect, test, type Page } from "@playwright/test";

const AUTHORIZED_TENANT = "00000000-0000-4000-8000-000000000001";

async function signInAndSelectTenant(page: Page) {
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in" }).click();
  await page.getByLabel("Username").fill("slice1-human");
  await page.getByRole("textbox", { name: "Password" }).fill("slice1-password");
  await page.getByRole("textbox", { name: "Password" }).press("Enter");
  await expect(page).toHaveURL(/\/operations$/);
  await page.getByLabel("Tenant ID").fill(AUTHORIZED_TENANT);
  const tenantSelection = page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === "POST" && new URL(response.url()).pathname === "/api/tenant/select";
  });
  await page.getByRole("button", { name: "Verify Tenant" }).click();
  await tenantSelection;
  await expect(page.getByRole("heading", { name: "Playwright Tenant", exact: true })).toBeVisible();
}

test("reads and updates versioned tenant configuration with confirmation and reason", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto("/operations/configuration");
  await expect(page.getByRole("heading", { name: "Tenant configuration", exact: true })).toBeVisible();

  await page.getByLabel("Allowed currencies").fill("SAR, USD");
  await page.getByLabel("Default locale").fill("en-SA");
  await page.getByLabel("Timezone").fill("Asia/Riyadh");
  await page.getByLabel("Display settings JSON").fill('{"dateFormat":"yyyy-MM-dd","compactTables":true}');
  await page.getByLabel("Reason").fill("Authenticated configuration E2E coverage");
  await page.getByLabel("I confirm this configuration change.").check();

  const updateResponsePromise = page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === "PUT" && new URL(response.url()).pathname === "/api/tenant/configuration";
  });
  await page.getByRole("button", { name: "Save configuration" }).click();
  const updateResponse = await updateResponsePromise;
  expect(updateResponse.status()).toBe(200);
  const updated = await updateResponse.json() as { version?: number; displaySettings?: Record<string, unknown> };
  expect(updated.version).toBeGreaterThan(0);
  expect(updated.displaySettings).toEqual({ dateFormat: "yyyy-MM-dd", compactTables: true });
  await expect(page.getByText(new RegExp(`Configuration saved as version ${updated.version}`))).toBeVisible();
  await expect(page.getByText(new RegExp(`Current version: ${updated.version}`))).toBeVisible();

  await page.reload();
  const persistedCurrencies = (await page.getByLabel("Allowed currencies").inputValue())
    .split(",")
    .map((currency) => currency.trim());
  expect(new Set(persistedCurrencies)).toEqual(new Set(["SAR", "USD"]));
  await expect(page.getByLabel("Default locale")).toHaveValue("en-SA");
  await expect(page.getByLabel("Timezone")).toHaveValue("Asia/Riyadh");
  await expect(page.getByLabel("Display settings JSON")).toHaveValue(/compactTables/);
  expect(page.url()).not.toContain("accessToken");
  expect(await page.content()).not.toContain("Bearer ");
});
