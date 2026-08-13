import { describe, expect, it } from "vitest";
import { formatOperationsDateTime } from "../lib/formatting";

describe("Operations Web date/time formatting", () => {
  const instant = "2026-08-01T21:30:00Z";

  it("uses the selected English locale and Tenant timezone", () => {
    const formatted = formatOperationsDateTime(instant, "en-SA", "Asia/Riyadh");

    expect(formatted).toContain("Aug");
    expect(formatted).toContain("12:30");
  });

  it("uses the selected Arabic locale and Tenant timezone", () => {
    const formatted = formatOperationsDateTime(instant, "ar-SA", "Asia/Riyadh");

    expect(formatted).toMatch(/[٠-٩]/);
    expect(formatted).toContain("١٢:٣٠");
  });

  it("falls back safely for invalid input or timezone", () => {
    expect(formatOperationsDateTime("not-a-date", "en-SA")).toBe("not-a-date");
    expect(formatOperationsDateTime(instant, "en-SA", "not/a-timezone")).toContain("Aug");
  });
});
