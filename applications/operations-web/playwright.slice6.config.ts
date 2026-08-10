import { defineConfig } from "@playwright/test";
import baseConfig from "./playwright.config";

const baseWebServer = baseConfig.webServer;
if (!baseWebServer || Array.isArray(baseWebServer)) {
  throw new Error("The shared Playwright configuration must define one web server");
}

export default defineConfig({
  ...baseConfig,
  testMatch: "reversal.spec.ts",
  webServer: {
    ...baseWebServer,
    env: {
      ...baseWebServer.env,
      E2E_SLICE6: "true",
    },
  },
});
