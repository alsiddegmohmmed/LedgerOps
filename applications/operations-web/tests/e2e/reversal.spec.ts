import { expect, test } from "@playwright/test";

const AUTHORIZED_TENANT = "00000000-0000-4000-8000-000000000001";
const PAYMENT_ID = "00000000-0000-4000-8000-000000000021";

async function signInAndSelectTenant(page: import("@playwright/test").Page) {
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

test("requests a full Reversal from an authenticated Payment detail page", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto(`/operations/payments/${PAYMENT_ID}`);

  await expect(page.getByRole("heading", { name: PAYMENT_ID })).toBeVisible();
  await expect(page.getByText("Status: COMPLETED")).toBeVisible();
  await expect(page.getByLabel("Reason for full Reversal")).toBeVisible();

  await page.getByLabel("Reason for full Reversal").fill("Authenticated Slice 6 browser coverage");
  await page.getByLabel("I confirm that the full completed Payment should be reversed.").check();
  const responsePromise = page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === "POST"
      && new URL(response.url()).pathname === `/api/payments/${PAYMENT_ID}/reversal`;
  });
  await page.getByRole("button", { name: "Request full Reversal" }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(201);

  await expect(page.getByRole("status")).toHaveText(
    "The Reversal was requested. Provider processing will continue asynchronously.",
  );
  await expect(page.getByText("Status: REQUESTED")).toBeVisible();
  await expect(page.getByText("Reversal ID:")).toBeVisible();
  await expect(page.getByLabel("Reason for full Reversal")).toHaveCount(0);

  await page.reload();
  await expect(page.getByText("Status: REQUESTED")).toBeVisible();
  await expect(page.getByText("Authenticated Slice 6 browser coverage")).toBeVisible();
  await expect(page.getByLabel("Reason for full Reversal")).toHaveCount(0);
});
