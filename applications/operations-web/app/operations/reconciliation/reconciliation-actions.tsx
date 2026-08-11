"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import type { CoreReconciliationRun, CoreSettlementBatch } from "../../../lib/core";

type ActionState = { kind: "idle" | "pending" | "success" | "error"; message?: string };

export function ReconciliationStartForm({
  batches,
  csrfToken,
}: {
  batches: CoreSettlementBatch[];
  csrfToken: string;
}) {
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const sourceCutoffValue = String(form.get("sourceCutoff") ?? "");
    const sourceCutoff = sourceCutoffValue ? new Date(sourceCutoffValue).toISOString() : "";
    setState({ kind: "pending", message: "Creating the immutable reconciliation snapshot…" });
    const response = await fetch("/api/reconciliation-runs", {
      method: "POST",
      headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
      body: JSON.stringify({
        batchVersionId: form.get("batchVersionId"),
        rulesVersion: form.get("rulesVersion"),
        sourceCutoff,
        confirmation: true,
      }),
    });
    const body = await response.json().catch(() => ({})) as { runId?: string; type?: string; login?: string };
    if (response.status === 401 && body.login) { window.location.href = body.login; return; }
    if (!response.ok) {
      setState({ kind: "error", message: reconciliationMessage(response.status, body.type) });
      return;
    }
    setState({ kind: "success", message: `Run ${body.runId ?? "created"} completed.` });
    event.currentTarget.reset();
    router.refresh();
  }

  return <section className="panel">
    <div className="eyebrow">Run reconciliation</div>
    <h2>Create an immutable run</h2>
    <p>The source cutoff is captured in the run. Re-running creates new evidence and never overwrites an earlier run.</p>
    {batches.length === 0 ? <p>No completed settlement batches are available.</p> : <form className="form-grid" onSubmit={submit}>
      <label>Completed batch
        <select name="batchVersionId" required defaultValue="">
          <option value="" disabled>Select a batch</option>
          {batches.filter((batch) => ["COMPLETED", "COMPLETED_WITH_DISCREPANCIES"].includes(batch.status)).map((batch) => (
            <option key={batch.batchVersionId} value={batch.batchVersionId}>{batch.providerBatchReference} · {batch.settlementPeriodStart} → {batch.settlementPeriodEnd}</option>
          ))}
        </select>
      </label>
      <label>Rules version<input name="rulesVersion" defaultValue="release-0.3-reconciliation-v1" required maxLength={64} /></label>
      <label>Source cutoff<input name="sourceCutoff" type="datetime-local" required /></label>
      <button type="submit" disabled={state.kind === "pending"}>Run reconciliation</button>
    </form>}
    <ActionFeedback state={state} />
  </section>;
}

export function ReconciliationRunActions({
  run,
  csrfToken,
  supportActive,
}: {
  run: CoreReconciliationRun;
  csrfToken: string;
  supportActive: boolean;
}) {
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const [reason, setReason] = useState("Slice 8 reconciliation operation");
  const router = useRouter();

  async function action(kind: "promote" | "prepare" | "post") {
    setState({ kind: "pending", message: `${kind === "promote" ? "Promoting" : kind === "prepare" ? "Preparing" : "Posting"} reconciliation evidence…` });
    const path = kind === "promote"
      ? `/api/reconciliation-runs/${run.runId}/promote`
      : kind === "prepare"
        ? `/api/reconciliation-runs/${run.runId}/postings/prepare`
        : `/api/reconciliation-runs/${run.runId}/postings`;
    const response = await fetch(path, {
      method: "POST",
      headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
      body: JSON.stringify({ batchFamilyId: run.batchFamilyId, confirmation: true, reason }),
    });
    const body = await response.json().catch(() => ({})) as { type?: string; login?: string };
    if (response.status === 401 && body.login) { window.location.href = body.login; return; }
    if (!response.ok) {
      setState({ kind: "error", message: reconciliationMessage(response.status, body.type) });
      return;
    }
    setState({ kind: "success", message: `${kind === "promote" ? "Promotion" : kind === "prepare" ? "Preparation" : "Posting"} completed.` });
    router.refresh();
  }

  const terminal = ["COMPLETED", "COMPLETED_WITH_DISCREPANCIES"].includes(run.status);
  return <section className="panel">
    <div className="eyebrow">Controlled actions</div>
    {supportActive ? <p>Support mode is read-only. Reconciliation actions are unavailable.</p> : <>
      <label>Reason<input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={512} /></label>
      <div className="button-row">
        <button type="button" onClick={() => void action("promote")} disabled={state.kind === "pending" || !terminal}>Promote as current</button>
        <button type="button" className="secondary" onClick={() => void action("prepare")} disabled={state.kind === "pending" || !terminal}>Prepare postings</button>
        <button type="button" className="secondary" onClick={() => void action("post")} disabled={state.kind === "pending" || !terminal}>Post exact matches</button>
      </div>
      <p className="table-secondary">Posting is explicit. Only the current run&apos;s exact matches are eligible; discrepancies never create Ledger effects.</p>
    </>}
    <ActionFeedback state={state} />
  </section>;
}

function ActionFeedback({ state }: { state: ActionState }) {
  if (state.kind === "idle") return null;
  return <div className={`action-feedback ${state.kind}`} role="alert"><p>{state.message}</p></div>;
}

function reconciliationMessage(status: number, type?: string) {
  if (status === 400) return type?.includes("request") ? "Correct the reconciliation request and try again." : "The reconciliation request is invalid.";
  if (status === 403) return "You are not authorized for this Tenant-wide reconciliation action.";
  if (status === 404) return "The reconciliation resource is outside your authorized Tenant scope.";
  if (status === 409) return "The reconciliation state changed. Refresh before trying the next action.";
  return "The reconciliation operation could not be completed.";
}
