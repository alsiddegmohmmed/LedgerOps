import { describe, expect, it } from "vitest";
import { validCsrfToken, validSameOrigin } from "../lib/csrf";

describe("BFF CSRF checks", () => {
  it("requires the session token and same origin", () => {
    const session = { csrfToken: "csrf" } as Parameters<typeof validCsrfToken>[0];
    expect(validCsrfToken(session, "csrf")).toBe(true);
    expect(validCsrfToken(session, "wrong")).toBe(false);
    expect(validSameOrigin(new Request("http://localhost", { headers: { origin: "http://localhost:3001" } }))).toBe(true);
    expect(validSameOrigin(new Request("http://localhost", { headers: { origin: "https://evil.example" } }))).toBe(false);
  });
});
