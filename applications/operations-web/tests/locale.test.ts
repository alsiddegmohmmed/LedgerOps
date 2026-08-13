import { describe, expect, it } from "vitest";
import { operationsDirection, operationsHtmlLocale, operationsLanguage } from "../lib/locale";

describe("Operations Web locale attributes", () => {
  it("maps Arabic BCP 47 locales to Arabic RTL", () => {
    const language = operationsLanguage("ar-SA");

    expect(operationsHtmlLocale("ar-SA")).toBe("ar-SA");
    expect(language).toBe("ar");
    expect(operationsDirection(language)).toBe("rtl");
  });

  it("maps English BCP 47 locales to English LTR", () => {
    const language = operationsLanguage("en-SA");

    expect(language).toBe("en");
    expect(operationsDirection(language)).toBe("ltr");
  });

  it("fails closed to English LTR for missing or unsupported locales", () => {
    const language = operationsLanguage(undefined);

    expect(operationsHtmlLocale("fr-FR")).toBe("en");
    expect(language).toBe("en");
    expect(operationsDirection(language)).toBe("ltr");
    expect(operationsLanguage("fr-FR")).toBe("en");
  });
});
