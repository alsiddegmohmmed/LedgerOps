"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import type { CoreSettlementBatch } from "../../../lib/core";

type ActionState = { kind: "idle" | "pending" | "success" | "error"; message?: string };

export function SettlementUploadForm({ csrfToken }: { csrfToken: string }) {
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState({ kind: "pending", message: "Uploading settlement file…" });
    const response = await fetch("/api/settlements/upload", {
      method: "POST",
      headers: { "x-csrf-token": csrfToken },
      body: new FormData(event.currentTarget),
    });
    const body = await response.json().catch(() => ({})) as { type?: string; login?: string; batchVersionId?: string };
    if (response.status === 401 && body.login) { window.location.href = body.login; return; }
    if (!response.ok) { setState({ kind: "error", message: settlementMessage(response.status, body.type) }); return; }
    setState({ kind: "success", message: `Uploaded batch ${body.batchVersionId ?? "successfully"}. Validate it before processing.` });
    event.currentTarget.reset();
    router.refresh();
  }

  return <section className="panel"><div className="eyebrow">Upload</div><h2>Provider settlement CSV</h2><p>Use the exact v1 CSV contract. Maximum file size is 50 MiB.</p><form className="form-grid" onSubmit={submit}>
    <label>Provider batch reference<input name="providerBatchReference" required maxLength={100} /></label>
    <label>Settlement period start<input name="settlementPeriodStart" type="date" required /></label>
    <label>Settlement period end<input name="settlementPeriodEnd" type="date" required /></label>
    <label>Corrects batch version (optional)<input name="supersedesBatchVersionId" pattern="[0-9a-fA-F-]{36}" /></label>
    <label>CSV file<input name="file" type="file" accept=".csv,text/csv" required /></label>
    <button type="submit" disabled={state.kind === "pending"}>Upload settlement file</button>
  </form><ActionFeedback state={state} /></section>;
}

export function SettlementBatchActions({ batch, csrfToken }: { batch: CoreSettlementBatch; csrfToken: string }) {
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const router = useRouter();

  async function action(kind: "validate" | "process") {
    setState({ kind: "pending", message: kind === "validate" ? "Validating settlement file…" : "Processing normalized settlement records…" });
    const response = await fetch(`/api/settlements/${batch.batchVersionId}/${kind}`, {
      method: "POST",
      headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
      body: kind === "process" ? JSON.stringify({ confirmation: true }) : undefined,
    });
    const body = await response.json().catch(() => ({})) as { type?: string; login?: string };
    if (response.status === 401 && body.login) { window.location.href = body.login; return; }
    if (!response.ok) { setState({ kind: "error", message: settlementMessage(response.status, body.type) }); return; }
    setState({ kind: "success", message: kind === "validate" ? "Validation completed." : "Processing completed." });
    router.refresh();
  }

  return <section className="panel"><div className="eyebrow">Lifecycle controls</div><div className="button-row">
    <button type="button" onClick={() => void action("validate")} disabled={state.kind === "pending" || !["RECEIVED", "FAILED"].includes(batch.status)}>Validate</button>
    <button type="button" className="secondary" onClick={() => void action("process")} disabled={state.kind === "pending" || batch.status !== "READY"}>Confirm processing</button>
  </div><p className="table-secondary">Processing is explicit and does not perform matching or Ledger posting.</p><ActionFeedback state={state} /></section>;
}

function ActionFeedback({ state }: { state: ActionState }) {
  if (state.kind === "idle") return null;
  return <div className={`action-feedback ${state.kind}`} role="alert"><p>{state.message}</p></div>;
}

function settlementMessage(status: number, type?: string) {
  if (status === 400) return type?.includes("file") ? "The CSV was rejected. Check its header, fields, size, and row limits." : "Correct the settlement request and try again.";
  if (status === 403) return "You are not authorized for settlement operations.";
  if (status === 404) return "The settlement batch is outside your authorized Tenant scope.";
  if (status === 409) return "The batch state changed. Refresh before trying the next action.";
  return "The settlement action could not be completed.";
}
