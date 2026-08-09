"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";

type ConfigurationInitialState = {
  allowedCurrencies: string[];
  defaultLocale: string;
  timezone: string;
  displaySettings: string;
};

type ActionState = {
  kind: "idle" | "pending" | "success" | "error";
  message?: string;
};

export function ConfigurationForm({
  csrfToken,
  initial,
}: {
  csrfToken: string;
  initial: ConfigurationInitialState;
}) {
  const [allowedCurrencies, setAllowedCurrencies] = useState(initial.allowedCurrencies.join(", "));
  const [defaultLocale, setDefaultLocale] = useState(initial.defaultLocale);
  const [timezone, setTimezone] = useState(initial.timezone);
  const [displaySettings, setDisplaySettings] = useState(initial.displaySettings);
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    let parsedDisplaySettings: unknown;
    try {
      parsedDisplaySettings = JSON.parse(displaySettings);
    } catch {
      setState({ kind: "error", message: "Display settings must be valid JSON." });
      return;
    }
    if (
      typeof parsedDisplaySettings !== "object"
      || parsedDisplaySettings === null
      || Array.isArray(parsedDisplaySettings)
    ) {
      setState({ kind: "error", message: "Display settings must be a JSON object." });
      return;
    }

    const currencies = allowedCurrencies
      .split(",")
      .map((currency) => currency.trim().toUpperCase())
      .filter(Boolean);
    if (currencies.length === 0) {
      setState({ kind: "error", message: "Enter at least one ISO currency code." });
      return;
    }

    setState({ kind: "pending", message: "Saving tenant configuration…" });
    try {
      const response = await fetch("/api/tenant/configuration", {
        method: "PUT",
        headers: {
          "content-type": "application/json",
          "x-csrf-token": csrfToken,
        },
        body: JSON.stringify({
          allowedCurrencies: currencies,
          defaultLocale,
          timezone,
          displaySettings: parsedDisplaySettings,
          reason,
          confirmation,
        }),
      });
      const body = await response.json().catch(() => ({})) as {
        type?: string;
        login?: string;
        version?: number;
      };
      if (response.status === 401 && body.login) {
        window.location.href = body.login;
        return;
      }
      if (!response.ok) {
        setState({ kind: "error", message: configurationMessage(response.status, body.type) });
        return;
      }
      setState({ kind: "success", message: `Configuration saved as version ${body.version ?? "new"}.` });
      setConfirmation(false);
      setReason("");
      router.refresh();
    } catch {
      setState({ kind: "error", message: "The configuration update could not reach Operations Core." });
    }
  }

  return (
    <section className="panel">
      <div className="eyebrow">Versioned settings</div>
      <h2>Update configuration</h2>
      <p>Use comma-separated ISO 4217 currency codes. The reason is retained in the audit record.</p>
      <form className="form-grid" onSubmit={submit}>
        <label>
          Allowed currencies
          <input
            value={allowedCurrencies}
            onChange={(event) => setAllowedCurrencies(event.target.value)}
            placeholder="SAR, USD"
            required
          />
        </label>
        <label>
          Default locale
          <input
            value={defaultLocale}
            onChange={(event) => setDefaultLocale(event.target.value)}
            placeholder="en-SA"
            maxLength={35}
            required
          />
        </label>
        <label>
          Timezone
          <input
            value={timezone}
            onChange={(event) => setTimezone(event.target.value)}
            placeholder="Asia/Riyadh"
            maxLength={100}
            required
          />
        </label>
        <label>
          Display settings JSON
          <textarea
            value={displaySettings}
            onChange={(event) => setDisplaySettings(event.target.value)}
            rows={7}
            spellCheck={false}
            required
          />
        </label>
        <label>
          Reason
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={512}
            rows={3}
            required
          />
        </label>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={confirmation}
            onChange={(event) => setConfirmation(event.target.checked)}
            required
          />
          I confirm this configuration change.
        </label>
        <button type="submit" disabled={state.kind === "pending"}>Save configuration</button>
      </form>
      {state.kind !== "idle" && (
        <div className={`action-feedback ${state.kind}`} role="alert">
          <p>{state.message}</p>
        </div>
      )}
    </section>
  );
}

function configurationMessage(status: number, type?: string) {
  if (status === 403) return "You are not authorized to change this Tenant configuration.";
  if (status === 404) return "This Tenant is outside your authorized scope.";
  if (status === 409) return "The Tenant configuration changed. Refresh and try again.";
  if (status === 400 || type === "invalid_request") return "Enter valid settings, a reason, and confirm the change.";
  if (status === 502 || status === 503) return "Operations Core is unavailable right now.";
  return "The Tenant configuration could not be updated.";
}
