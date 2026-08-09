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

test("shows the selected Tenant memberships without exposing invitation secrets", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto("/operations/memberships");

  await expect(page.getByRole("heading", { name: "Memberships", exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Linked" })).toBeVisible();
  await expect(page.getByRole("row", { name: /TENANT_ADMIN/ })).toBeVisible();
  expect(page.url()).not.toContain("accessToken");
  expect(await page.content()).not.toContain("Bearer ");
  expect(await page.content()).not.toContain("tokenHash");
});

test("revokes a pending invitation with confirmation and an audit reason", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto("/operations/memberships");

  const invitedRow = page.getByRole("row", { name: /invite@example\.com/ });
  await expect(invitedRow).toBeVisible();
  await invitedRow.getByLabel("Invitation revocation reason").fill("No longer required");
  await invitedRow.getByLabel("Confirm revocation").check();
  await invitedRow.getByRole("button", { name: "Revoke invitation" }).click();

  await expect(page.getByRole("row", { name: /invite@example\.com.*REVOKED/ })).toBeVisible();
  expect(await page.content()).not.toContain("aaaaaaaaaaaaaaaaaaaaaaaa");
});
