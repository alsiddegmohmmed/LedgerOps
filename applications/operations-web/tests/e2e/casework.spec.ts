import { expect, test } from "@playwright/test";

const AUTHORIZED_TENANT = "00000000-0000-4000-8000-000000000001";
const CASE_ID = "90000000-0000-4000-8000-000000000001";

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

test("requires explicit confirmation for correction and supports close/reopen from Case detail", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto("/operations/cases");
  await expect(page.getByRole("heading", { name: "Cases" })).toBeVisible();
  const detailLink = page.getByRole("link", { name: CASE_ID });
  await expect(detailLink).toBeVisible();
  const detailUrl = await detailLink.getAttribute("href");
  expect(detailUrl).toBe(`/operations/cases/${CASE_ID}`);
  await detailLink.click();

  await expect(page.getByRole("heading", { name: CASE_ID })).toBeVisible();
  await expect(page.getByLabel("I confirm this exact financial correction.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Request exact correction" })).toBeDisabled();

  await page.getByLabel("Resolution note").fill("The discrepancy is not actionable after investigation.");
  await expect(page.getByRole("button", { name: "Resolve case" })).toBeDisabled();
  await page.getByLabel("I confirm this Case resolution.").check();
  const resolutionResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith(`/api/cases/${CASE_ID}/resolution`));
  await page.getByRole("button", { name: "Resolve case" }).click();
  await resolutionResponse;
  await expect(page.locator("span.status-badge").filter({ hasText: "RESOLVED" })).toBeVisible();

  await page.getByLabel("Closure reason").fill("Investigation complete.");
  await expect(page.getByRole("button", { name: "Close case" })).toBeDisabled();
  await page.getByLabel("I confirm this Case should be closed.").check();
  await page.getByRole("button", { name: "Close case" }).click();
  await expect(page.locator("span.status-badge").filter({ hasText: "CLOSED" })).toBeVisible();

  await page.goto(detailUrl!);
  await expect(page.locator("span.status-badge").filter({ hasText: "CLOSED" })).toBeVisible();
  await page.getByLabel("Next status").selectOption("REOPENED");
  await page.getByLabel("Transition reason").fill("Additional evidence requires renewed investigation.");
  await expect(page.getByRole("button", { name: "Change status" })).toBeDisabled();
  await page.getByLabel("I confirm this Case should be reopened.").check();
  await page.getByRole("button", { name: "Change status" }).click();
  await expect(page.locator("span.status-badge").filter({ hasText: "REOPENED" })).toBeVisible();
  await expect(page.getByText(/STATUS_CHANGED/)).toBeVisible();
});
