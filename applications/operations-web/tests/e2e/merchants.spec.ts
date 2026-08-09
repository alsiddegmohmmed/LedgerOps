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
  const tenantNavigation = page.waitForNavigation({ waitUntil: "domcontentloaded" });
  await page.getByRole("button", { name: "Verify Tenant" }).click();
  await tenantNavigation;
  await expect(page.getByText("Playwright Tenant")).toBeVisible();
}

test("shows only the selected Tenant Merchants without exposing the bearer token", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto("/operations/merchants");

  await expect(page.getByRole("heading", { name: "Merchants", exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "ACTIVE" })).toBeVisible();
  await expect(page.getByRole("row", { name: /Playwright Merchant/ })).toBeVisible();
  expect(page.url()).not.toContain("accessToken");
  expect(await page.content()).not.toContain("Bearer ");
});
