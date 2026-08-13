export const DEFAULT_OPERATIONS_TIMEZONE = "Asia/Riyadh";

export function formatOperationsDateTime(
  value: string,
  locale: string,
  timezone: string = DEFAULT_OPERATIONS_TIMEZONE,
): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;

  try {
    return new Intl.DateTimeFormat(locale, {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: timezone,
    }).format(date);
  } catch {
    return new Intl.DateTimeFormat("en", {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: DEFAULT_OPERATIONS_TIMEZONE,
    }).format(date);
  }
}
