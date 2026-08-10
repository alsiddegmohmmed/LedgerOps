"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import type { CoreMerchant, CoreWebhookDelivery, CoreWebhookEndpoint } from "../../../lib/core";

const eventTypes = [
  "payment.completed",
  "payment.failed",
  "payment.reversed",
  "risk.review.required",
  "reconciliation.discrepancy.created",
];

type ActionState = { kind: "idle" | "pending" | "success" | "error"; message?: string; secret?: string };

export function WebhookCreateForm({ merchants, csrfToken }: { merchants: CoreMerchant[]; csrfToken: string }) {
  const [merchantId, setMerchantId] = useState(merchants[0]?.merchantId ?? "");
  const [label, setLabel] = useState("");
  const [endpointUrl, setEndpointUrl] = useState("");
  const [selectedEvents, setSelectedEvents] = useState(eventTypes);
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState({ kind: "pending", message: "Creating webhook endpoint…" });
    const result = await postAction("/api/webhooks/create", csrfToken, {
      merchantId, label, endpointUrl, allowedEventTypes: selectedEvents, confirmation,
    });
    if (result.kind === "session") {
      window.location.href = result.login;
      return;
    }
    if (result.kind === "error") {
      setState({ kind: "error", message: result.message });
      return;
    }
    setState({ kind: "success", message: "Endpoint created. Store this secret now; it will not be shown again.", secret: result.body.plaintextSecret });
    setConfirmation(false);
    router.refresh();
  }

  return (
    <section className="panel">
      <div className="eyebrow">Create</div>
      <h2>Merchant webhook endpoint</h2>
      <p>Core validates HTTPS and SSRF controls. The HMAC secret is disclosed only in the create or rotate response.</p>
      <form className="form-grid" onSubmit={submit}>
        <label>Merchant<select value={merchantId} onChange={(event) => setMerchantId(event.target.value)} required>{merchants.map((merchant) => <option key={merchant.merchantId} value={merchant.merchantId}>{merchant.name} ({merchant.merchantId})</option>)}</select></label>
        <label>Label<input value={label} onChange={(event) => setLabel(event.target.value)} maxLength={120} required /></label>
        <label>HTTPS endpoint URL<input type="url" value={endpointUrl} onChange={(event) => setEndpointUrl(event.target.value)} placeholder="https://example.test/ledgerops" required /></label>
        <fieldset><legend>Allowed event types</legend>{eventTypes.map((eventType) => <label className="checkbox-label" key={eventType}><input type="checkbox" checked={selectedEvents.includes(eventType)} onChange={(event) => setSelectedEvents(event.target.checked ? [...selectedEvents, eventType] : selectedEvents.filter((item) => item !== eventType))} />{eventType}</label>)}</fieldset>
        <label className="checkbox-label"><input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} required />I confirm this endpoint should be created.</label>
        <button type="submit" disabled={state.kind === "pending" || selectedEvents.length === 0}>Create endpoint</button>
      </form>
      <ActionFeedback state={state} />
    </section>
  );
}

export function WebhookEndpointActions({ endpoint, merchantId, csrfToken }: { endpoint: CoreWebhookEndpoint; merchantId: string; csrfToken: string }) {
  const [eventType, setEventType] = useState(endpoint.allowedEventTypes[0] ?? eventTypes[0]);
  const [payload, setPayload] = useState('{"source":"operations-web","message":"synthetic test"}');
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const router = useRouter();

  async function action(kind: "rotate" | "revoke" | "test") {
    let body: Record<string, unknown> = { merchantId, endpointId: endpoint.endpointId, confirmation };
    if (kind === "test") {
      let parsed: Record<string, unknown>;
      try {
        parsed = JSON.parse(payload) as Record<string, unknown>;
      } catch {
        setState({ kind: "error", message: "Test payload must be valid JSON." });
        return;
      }
      body = { merchantId, endpointId: endpoint.endpointId, eventType, payload: parsed };
    }
    setState({ kind: "pending", message: `${kind === "rotate" ? "Rotating" : kind === "revoke" ? "Revoking" : "Creating test delivery"}…` });
    const result = await postAction(`/api/webhooks/${kind}`, csrfToken, body);
    if (result.kind === "session") {
      window.location.href = result.login;
      return;
    }
    if (result.kind === "error") {
      setState({ kind: "error", message: result.message });
      return;
    }
    setState({ kind: "success", message: kind === "test" ? "Synthetic delivery created." : kind === "rotate" ? "Secret rotated. Store the new secret now." : "Endpoint revoked." , secret: result.body.plaintextSecret });
    setConfirmation(false);
    router.refresh();
  }

  return (
    <div className="action-stack">
      {endpoint.status === "ACTIVE" && <>
        <label>Test event<select value={eventType} onChange={(event) => setEventType(event.target.value)}>{endpoint.allowedEventTypes.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Test payload<textarea value={payload} onChange={(event) => setPayload(event.target.value)} rows={3} /></label>
        <button type="button" disabled={state.kind === "pending"} onClick={() => void action("test")}>Send synthetic test</button>
        <label className="checkbox-label compact"><input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} />Confirm endpoint action</label>
        <div className="button-row"><button type="button" disabled={state.kind === "pending" || !confirmation} onClick={() => void action("rotate")}>Rotate secret</button><button className="danger" type="button" disabled={state.kind === "pending" || !confirmation} onClick={() => void action("revoke")}>Revoke</button></div>
      </>}
      <ActionFeedback state={state} />
    </div>
  );
}

function ActionFeedback({ state }: { state: ActionState }) {
  if (state.kind === "idle") return null;
  return <div className={`action-feedback ${state.kind}`} role="status"><p>{state.message}</p>{state.secret && <><p><strong>One-time webhook secret</strong></p><code className="secret-value">{state.secret}</code></>}</div>;
}

async function postAction(path: string, csrfToken: string, body: Record<string, unknown>) {
  try {
    const response = await fetch(path, { method: "POST", headers: { "content-type": "application/json", "x-csrf-token": csrfToken }, body: JSON.stringify(body) });
    const responseBody = await response.json().catch(() => ({})) as { type?: string; login?: string; plaintextSecret?: string };
    if (response.status === 401 && responseBody.login) return { kind: "session" as const, login: responseBody.login };
    if (!response.ok) return { kind: "error" as const, message: response.status === 403 ? "You are not authorized for this Merchant webhook." : response.status === 404 ? "The endpoint is outside your Merchant scope." : `Webhook action failed (${responseBody.type ?? response.status}).` };
    return { kind: "ok" as const, body: responseBody };
  } catch {
    return { kind: "error" as const, message: "The webhook action could not reach Operations Core." };
  }
}

export type WebhookDeliveryMap = Record<string, CoreWebhookDelivery[]>;
