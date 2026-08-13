export type OperationsLanguage = "ar" | "en";
export type OperationsDirection = "ltr" | "rtl";

const supportedLocale = /^(ar|en)(?:-[A-Za-z0-9]{2,8})*$/i;

export function operationsHtmlLocale(locale: string | undefined): string {
  const normalized = locale?.trim();
  return normalized && supportedLocale.test(normalized) ? normalized : "en";
}

export function operationsLanguage(locale: string | undefined): OperationsLanguage {
  return operationsHtmlLocale(locale).toLowerCase().startsWith("ar") ? "ar" : "en";
}

export function operationsDirection(language: OperationsLanguage): OperationsDirection {
  return language === "ar" ? "rtl" : "ltr";
}
