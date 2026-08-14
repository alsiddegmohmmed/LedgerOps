import { execFileSync } from "node:child_process";
import { resolve } from "node:path";
import { defineConfig, devices } from "@playwright/test";

const ports = JSON.parse(execFileSync(process.execPath, [resolve("tests/e2e/allocate-ports.mjs")], { encoding: "utf8" })) as Record<string, number>;
for (const [name, port] of Object.entries(ports)) process.env[`E2E_${name.toUpperCase()}_PORT`] = String(port);
const baseURL = `http://127.0.0.1:${ports.bff}`;
const webServerEnv = Object.fromEntries(Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined));

export default defineConfig({
  testDir: "./tests/e2e",
  testIgnore: ["casework.spec.ts", "reversal.spec.ts"],
  workers: 1,
  globalTeardown: "./tests/e2e/global-teardown.mjs",
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    trace: "retain-on-failure",
  },
  webServer: {
    command: "node tests/e2e/test-app.mjs",
    url: baseURL,
    timeout: 180_000,
    reuseExistingServer: false,
    env: webServerEnv,
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
