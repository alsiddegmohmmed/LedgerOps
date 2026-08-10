"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { type CoreCase } from "../../../lib/core";

type State = { kind: "idle" | "pending" | "success" | "error"; message?: string };
const resolutions = [
  "RISK_APPROVE", "RISK_REJECT", "PROVIDER_ERROR", "INTERNAL_PROCESSING_ERROR",
  "DUPLICATE_EXTERNAL_RECORD", "EXPECTED_TIMING_DIFFERENCE", "FALSE_POSITIVE",
] as const;
const transitions: CoreCase["status"][] = ["INVESTIGATING", "AWAITING_INFORMATION", "RESOLVED", "CLOSED", "REOPENED"];

export function CaseActions({ caseFile, csrfToken, readOnly }: { caseFile: CoreCase; csrfToken: string; readOnly: boolean }) {
  const router = useRouter();
  const [ownerId, setOwnerId] = useState(caseFile.ownerId ?? "");
  const [reason, setReason] = useState("");
  const [note, setNote] = useState("");
  const [resolution, setResolution] = useState<(typeof resolutions)[number]>(resolutions[0]);
  const [target, setTarget] = useState<CoreCase["status"]>(defaultTarget(caseFile.status));
  const [state, setState] = useState<State>({ kind: "idle" });

  async function submit(path: string, body: Record<string, unknown>, success: string) {
    setState({ kind: "pending", message: "Saving case change…" });
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify(body),
      });
      const payload = await response.json().catch(() => ({})) as { login?: string };
      if (response.status === 401 && payload.login) { window.location.href = payload.login; return; }
      if (!response.ok) {
        setState({ kind: "error", message: response.status === 403 ? "You are not authorized for this action." : "The case changed or the action was rejected." });
        return;
      }
      setState({ kind: "success", message: success });
      setReason(""); setNote(""); router.refresh();
    } catch { setState({ kind: "error", message: "Operations Core could not be reached." }); }
  }

  if (readOnly) return <span className="table-secondary">Read-only support mode</span>;

  return (
    <div className="action-stack">
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/assignment`, { ownerId, reason }, "Case assigned."); }}>
        <label>Owner ID<input value={ownerId} onChange={(event) => setOwnerId(event.target.value)} required pattern="[0-9a-fA-F-]{36}" /></label>
        <label>Assignment reason<textarea value={reason} onChange={(event) => setReason(event.target.value)} required maxLength={512} rows={2} /></label>
        <button type="submit" disabled={state.kind === "pending"}>Assign / reassign</button>
      </form>
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/transitions`, { target, reason }, "Case status updated."); }}>
        <label>Next status<select value={target} onChange={(event) => setTarget(event.target.value as CoreCase["status"])}>{transitions.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Transition reason<textarea value={reason} onChange={(event) => setReason(event.target.value)} required maxLength={512} rows={2} /></label>
        <button className="secondary" type="submit" disabled={state.kind === "pending"}>Change status</button>
      </form>
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/notes`, { note }, "Investigation note added."); }}>
        <label>Investigation note<textarea value={note} onChange={(event) => setNote(event.target.value)} required maxLength={4000} rows={3} /></label>
        <button className="secondary" type="submit" disabled={state.kind === "pending"}>Add note</button>
      </form>
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/resolution`, { resolution, note }, "Case resolved."); }}>
        <label>Resolution<select value={resolution} onChange={(event) => setResolution(event.target.value as typeof resolution)}>{resolutions.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Resolution note<textarea value={note} onChange={(event) => setNote(event.target.value)} required maxLength={4000} rows={3} /></label>
        <button type="submit" disabled={state.kind === "pending" || caseFile.status === "CLOSED"}>Resolve case</button>
      </form>
      <form onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/close`, { reason }, "Case closed."); }}>
        <button className="danger" type="submit" disabled={state.kind === "pending" || caseFile.status !== "RESOLVED"}>Close case</button>
      </form>
      {state.kind !== "idle" && <div className={`action-feedback ${state.kind}`} role="alert"><p>{state.message}</p></div>}
    </div>
  );
}

function defaultTarget(status: CoreCase["status"]): CoreCase["status"] {
  if (status === "OPEN") return "INVESTIGATING";
  if (status === "AWAITING_INFORMATION") return "INVESTIGATING";
  if (status === "RESOLVED") return "CLOSED";
  if (status === "CLOSED") return "REOPENED";
  if (status === "REOPENED") return "INVESTIGATING";
  return "AWAITING_INFORMATION";
}
