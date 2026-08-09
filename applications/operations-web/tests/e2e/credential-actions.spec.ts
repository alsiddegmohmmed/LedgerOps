import { expect, test, type Page } from "@playwright/test";

const AUTHORIZED_TENANT = "00000000-0000-4000-8000-000000000001";
const PLAYWRIGHT_MERCHANT = "00000000-0000-4000-8000-000000000011";

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

function actionResponse(page: Page, path: string) {
  return page.waitForResponse((response) => {
    const request = response.request();
    return request.method() === "POST" && new URL(response.url()).pathname === path;
  });
}

test("creates, lists, rotates, revokes, and does not redisplay credential secrets", async ({ page }) => {
  await signInAndSelectTenant(page);
  await page.goto("/operations/credentials");
  await expect(page.getByRole("heading", { name: "Playwright Tenant" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "New sandbox credential" })).toBeVisible();

  const label = `Playwright E2E ${Date.now()}`;
  await page.getByLabel("Merchant ID").fill(PLAYWRIGHT_MERCHANT);
  await page.getByLabel("Label").fill(label);
  await page.getByLabel("Reason").fill("Authenticated Playwright credential lifecycle coverage");
  await page.getByLabel("I confirm that this credential should be created.").check();
  const createResponsePromise = actionResponse(page, "/api/credentials/create");
  await page.getByRole("button", { name: "Create credential" }).click();
  const createResponse = await createResponsePromise;
  expect(createResponse.status()).toBe(201);
  const created = await createResponse.json() as { clientSecret?: string };
  expect(created.clientSecret).toBeTruthy();
  const firstSecret = created.clientSecret!;
  await expect(page.getByText("One-time client secret")).toBeVisible();
  await expect(page.locator("code.secret-value")).toHaveText(firstSecret);
  const createdRow = page.locator("tbody tr").filter({ hasText: label });
  await expect(createdRow).toBeVisible();
  expect(page.url()).not.toContain(firstSecret);

  await page.reload();
  await expect(page.locator("tbody tr").filter({ hasText: label })).toBeVisible();
  await expect(page.getByText("One-time client secret")).toHaveCount(0);
  await expect(page.locator("code.secret-value")).toHaveCount(0);
  expect(await page.content()).not.toContain(firstSecret);

  const activeRow = page.locator("tbody tr").filter({ hasText: label }).filter({ hasText: "ACTIVE" });
  await activeRow.getByPlaceholder("Reason").fill("Rotate credential during authenticated E2E coverage");
  await activeRow.getByRole("checkbox").check();
  const rotateResponsePromise = actionResponse(page, "/api/credentials/rotate");
  await activeRow.getByRole("button", { name: "Rotate" }).click();
  const rotateResponse = await rotateResponsePromise;
  expect(rotateResponse.status()).toBe(201);
  const rotated = await rotateResponse.json() as { clientSecret?: string };
  expect(rotated.clientSecret).toBeTruthy();
  const replacementSecret = rotated.clientSecret!;
  expect(replacementSecret).not.toBe(firstSecret);
  await expect(page.getByText("One-time client secret")).toBeVisible();
  await expect(page.locator("code.secret-value")).toHaveText(replacementSecret);
  expect(page.url()).not.toContain(replacementSecret);

  await page.reload();
  await expect(page.getByText("One-time client secret")).toHaveCount(0);
  await expect(page.locator("code.secret-value")).toHaveCount(0);
  expect(await page.content()).not.toContain(firstSecret);
  expect(await page.content()).not.toContain(replacementSecret);

  const replacementRow = page.locator("tbody tr").filter({ hasText: label }).filter({ hasText: "ACTIVE" });
  await replacementRow.getByPlaceholder("Reason").fill("Revoke replacement credential during authenticated E2E coverage");
  await replacementRow.getByRole("checkbox").check();
  const revokeResponsePromise = actionResponse(page, "/api/credentials/revoke");
  await replacementRow.getByRole("button", { name: "Revoke" }).click();
  const revokeResponse = await revokeResponsePromise;
  expect(revokeResponse.status()).toBe(200);
  await expect(page.getByText("Credential revoked locally.")).toBeVisible();

  await page.reload();
  const revokedRows = page.locator("tbody tr").filter({ hasText: label }).filter({ hasText: "REVOKED" });
  await expect(revokedRows).toHaveCount(2);
  await expect(revokedRows.first().getByText("No further actions")).toBeVisible();
  await expect(page.getByText("One-time client secret")).toHaveCount(0);
  expect(await page.content()).not.toContain(firstSecret);
  expect(await page.content()).not.toContain(replacementSecret);
});
