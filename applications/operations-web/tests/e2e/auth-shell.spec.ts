import { expect, test } from "@playwright/test";

test("public shell does not expose browser bearer tokens", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Secure operational access." })).toBeVisible();
  await expect(page.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/api/auth/login");
  expect(await page.evaluate(() => ({ local: localStorage.length, session: sessionStorage.length }))).toEqual({ local: 0, session: 0 });
});
