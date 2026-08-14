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

test("creates and versions an operational contact without exposing the bearer token", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto("/operations/contacts");
  await expect(page.getByRole("heading", { name: "Operational contacts", exact: true })).toBeVisible();

  await page.getByLabel("Display name").fill("Settlement Operations");
  await page.getByLabel("Email").fill("SETTLEMENT@EXAMPLE.COM");
  await page.getByLabel("Purpose").fill("settlement");
  await page.getByLabel("Reason").fill("Authenticated operational-contact E2E coverage");
  await page.getByLabel("I confirm this operational-contact change.").check();

  const firstUpdate = page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === "PUT" && new URL(response.url()).pathname === "/api/tenant/operational-contacts";
  });
  await page.getByRole("button", { name: "Save contact" }).last().click();
  const firstResponse = await firstUpdate;
  expect(firstResponse.status()).toBe(200);
  const created = await firstResponse.json() as { contactId?: string; version?: number; email?: string };
  expect(created.contactId).toBeTruthy();
  expect(created.version).toBe(1);
  expect(created.email).toBe("settlement@example.com");
  await expect(page.getByText("Contact saved as version 1.")).toBeVisible();

  const contactPanel = page.locator("section.panel").filter({ hasText: "Settlement Operations" }).first();
  await contactPanel.getByLabel("Reason").fill("Deactivate settlement contact during E2E coverage");
  await contactPanel.getByLabel("Contact is active").uncheck();
  await contactPanel.getByLabel("I confirm this operational-contact change.").check();
  const secondUpdate = page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === "PUT" && new URL(response.url()).pathname === "/api/tenant/operational-contacts";
  });
  await contactPanel.getByRole("button", { name: "Save contact" }).click();
  const secondResponse = await secondUpdate;
  expect(secondResponse.status()).toBe(200);
  const updated = await secondResponse.json() as { version?: number; active?: boolean };
  expect(updated.version).toBe(2);
  expect(updated.active).toBe(false);
  await expect(page.getByText("Contact saved as version 2.")).toBeVisible();

  await page.reload();
  const persistedPanel = page.locator("section.panel").filter({ hasText: "Settlement Operations" }).first();
  await expect(persistedPanel.getByLabel("Email")).toHaveValue("settlement@example.com");
  await expect(persistedPanel.getByLabel("Contact is active")).not.toBeChecked();
  expect(page.url()).not.toContain("accessToken");
  expect(await page.content()).not.toContain("Bearer ");
});
