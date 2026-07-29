import { expect, test } from "@playwright/test";
import Redis from "ioredis";

const AUTHORIZED_TENANT = "00000000-0000-4000-8000-000000000001";
const UNAUTHORIZED_TENANT = "00000000-0000-4000-8000-000000000002";
const NONEXISTENT_TENANT = "00000000-0000-4000-8000-000000000099";

function sanitizeResponseBody(body: string): string {
  try { return JSON.stringify(JSON.parse(body)); } catch { return body.replace(/\s+/g, " ").trim(); }
}

async function selectTenant(page: import("@playwright/test").Page, tenantId: string) {
  await page.getByLabel("Tenant ID").fill(tenantId);
  const responsePromise = page.waitForResponse((response) => new URL(response.url()).pathname === "/api/tenant/select");
  await page.getByRole("button", { name: "Verify Tenant" }).click();
  const response = await responsePromise;
  await page.waitForLoadState("domcontentloaded", { timeout: 10_000 });
  return {
    status: response.status(),
    contentType: response.headers()["content-type"] ?? "",
    body: sanitizeResponseBody(await response.text()),
    redirect: response.headers().location ?? null,
    browserUrl: page.url(),
    visible: await page.locator("body").innerText(),
  };
}

test("public shell does not expose browser bearer tokens", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Secure operational access." })).toBeVisible();
  await expect(page.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/api/auth/login");
  const browserStorage = await page.evaluate(() => ({
    local: Object.entries(localStorage),
    session: Object.entries(sessionStorage),
  }));
  expect(JSON.stringify(browserStorage)).not.toMatch(/access_token|refresh_token|bearer/i);
});

test("completes PKCE login, verifies Tenant selection, and invalidates logout session", async ({ page, context }) => {
  const observedUrls: string[] = [];
  const responseUrls: string[] = [];
  const navigationUrls: string[] = [];
  const popupUrls: string[] = [];
  page.on("request", (request) => observedUrls.push(request.url()));
  page.on("response", (response) => {
    let request = response.request();
    while (request) {
      responseUrls.push(request.url());
      request = request.redirectedFrom()!;
    }
    responseUrls.push(response.url());
  });
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) navigationUrls.push(frame.url());
  });
  page.on("popup", (popup) => popup.on("framenavigated", (frame) => popupUrls.push(frame.url())));
  await page.goto("/");
  const authorizationRequest = page.waitForRequest((request) => request.url().includes("/protocol/openid-connect/auth"));
  await page.getByRole("link", { name: "Sign in" }).click();
  const authorizationUrl = new URL((await authorizationRequest).url());
  expect(authorizationUrl.searchParams.get("response_type")).toBe("code");
  expect(authorizationUrl.searchParams.get("code_challenge")).toBeTruthy();
  expect(authorizationUrl.searchParams.get("code_challenge_method")).toBe("S256");
  expect(authorizationUrl.searchParams.get("state")).toBeTruthy();
  expect(authorizationUrl.searchParams.get("nonce")).toBeTruthy();
  await page.getByLabel("Username").fill("slice1-human");
  await page.getByRole("textbox", { name: "Password" }).fill("slice1-password");
  await page.getByRole("button", { name: "Sign In" }).click();
  await expect(page).toHaveURL(/\/operations$/);

  const sessionCookie = (await context.cookies()).find((cookie) => cookie.name === "__Host-ledgerops_session");
  if (!sessionCookie) throw new Error("Expected the opaque session cookie after login");
  expect(sessionCookie).toMatchObject({ httpOnly: true, secure: true, sameSite: "Lax", path: "/" });
  const browserStorage = await page.evaluate(() => ({
    local: Object.entries(localStorage),
    session: Object.entries(sessionStorage),
  }));
  const browserData = await page.content();
  const visibleText = await page.locator("body").innerText();
  const scriptData = await page.locator("script").allTextContents();
  const browserCookieData = await page.evaluate(() => document.cookie);

  const redis = new Redis(`redis://127.0.0.1:${process.env.E2E_REDIS_PORT}`);
  const sessionKey = `operations-web:session:${sessionCookie.value}`;
  const storedSession = JSON.parse((await redis.get(sessionKey))!);
  expect(storedSession.accessToken).toBeTruthy();
  expect(storedSession.refreshToken).toBeTruthy();
  expect(storedSession.idToken).toBeTruthy();
  expect(storedSession.selectedTenantId).toBeUndefined();
  const currentUrl = page.url();
  const allObservedUrls = [...observedUrls, ...responseUrls, ...navigationUrls, ...popupUrls, currentUrl];
  for (const token of [storedSession.accessToken, storedSession.refreshToken, storedSession.idToken]) {
    expect(allObservedUrls.some((url) => url.includes(token))).toBe(false);
    expect(browserData.includes(token)).toBe(false);
    expect(visibleText.includes(token)).toBe(false);
    expect(JSON.stringify(browserStorage).includes(token)).toBe(false);
    expect(browserCookieData.includes(token)).toBe(false);
    expect(scriptData.join("\n").includes(token)).toBe(false);
  }

  await page.getByLabel("Tenant ID").fill(AUTHORIZED_TENANT);
  await page.getByRole("button", { name: "Verify Tenant" }).click();
  await expect(page.getByText("Playwright Tenant")).toBeVisible();
  expect(JSON.parse((await redis.get(sessionKey))!).selectedTenantId).toBe(AUTHORIZED_TENANT);

  await page.goto("/operations");
  const unauthorizedResponse = await selectTenant(page, UNAUTHORIZED_TENANT);

  await page.goto("/operations");
  const nonexistentResponse = await selectTenant(page, NONEXISTENT_TENANT);
  expect(unauthorizedResponse).toEqual(nonexistentResponse);
  expect(unauthorizedResponse.body).toContain("tenant_unavailable");

  await page.goto("/operations");
  await page.getByRole("button", { name: "Log out" }).click();
  await expect(page.getByRole("heading", { name: "Secure operational access." })).toBeVisible();
  expect((await context.cookies()).find((cookie) => cookie.name === "__Host-ledgerops_session")).toBeUndefined();
  expect(await redis.get(sessionKey)).toBeNull();
  await redis.quit();
  const response = await page.request.get("/operations", { maxRedirects: 0 });
  expect(response.status()).toBe(307);
  expect(response.headers().location).toContain("/api/auth/login");
});

test("rejects altered OAuth state and authorization code callbacks", async ({ page, context }) => {
  const redis = new Redis(`redis://127.0.0.1:${process.env.E2E_REDIS_PORT}`);
  await page.goto("/");
  const alteredState = new URL("/api/auth/callback", page.url());
  const state = "callback-state-original";
  alteredState.searchParams.set("state", `${state}-altered`);
  alteredState.searchParams.set("code", "invalid-authorization-code");
  await page.goto(alteredState.toString());
  expect(page.url()).toContain("error=invalid_callback");
  expect((await context.cookies()).find((cookie) => cookie.name === "__Host-ledgerops_session")).toBeUndefined();
  const invalidCodeState = "callback-state-code";
  const invalidCodeCallback = new URL("/api/auth/callback", page.url());
  await redis.set(`operations-web:oauth:${invalidCodeState}`, JSON.stringify({
    state: invalidCodeState, nonce: "nonce", codeVerifier: "verifier", createdAt: Date.now(),
  }), "EX", 300);
  invalidCodeCallback.searchParams.set("state", invalidCodeState);
  invalidCodeCallback.searchParams.set("code", "altered-authorization-code");
  await page.goto(invalidCodeCallback.toString());
  expect(page.url()).toContain("error=authentication_failed");
  await page.goto(invalidCodeCallback.toString());
  expect(page.url()).toContain("error=invalid_callback");
  await redis.quit();
});

if (process.env.E2E_FORCE_PLAYWRIGHT_FAILURE === "true") {
  test("intentional E2E failure for cleanup verification", () => {
    throw new Error("Intentional Playwright cleanup verification failure");
  });
}
