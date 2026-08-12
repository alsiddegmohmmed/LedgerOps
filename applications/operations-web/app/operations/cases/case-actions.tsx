"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { type CoreCase } from "../../../lib/core";

type State = { kind: "idle" | "pending" | "success" | "error"; message?: string };
const riskResolutions = ["RISK_APPROVE", "RISK_REJECT"] as const;
const reconciliationResolutions = [
  "PROVIDER_ERROR", "INTERNAL_PROCESSING_ERROR", "DUPLICATE_EXTERNAL_RECORD",
  "EXPECTED_TIMING_DIFFERENCE", "APPROVED_CORRECTION", "FALSE_POSITIVE",
] as const;
const transitions: CoreCase["status"][] = ["INVESTIGATING", "AWAITING_INFORMATION", "RESOLVED", "CLOSED", "REOPENED"];

export function CaseActions({ caseFile, csrfToken, readOnly }: { caseFile: CoreCase; csrfToken: string; readOnly: boolean }) {
  const router = useRouter();
  const [ownerId, setOwnerId] = useState(caseFile.ownerId ?? "");
  const [assignmentReason, setAssignmentReason] = useState("");
  const [transitionReason, setTransitionReason] = useState("");
  const [transitionConfirmation, setTransitionConfirmation] = useState(false);
  const [investigationNote, setInvestigationNote] = useState("");
  const [resolutionNote, setResolutionNote] = useState("");
  const [resolutionConfirmation, setResolutionConfirmation] = useState(false);
  const [settlementPostingId, setSettlementPostingId] = useState("");
  const [originalLedgerTransactionId, setOriginalLedgerTransactionId] = useState("");
  const [correctionReason, setCorrectionReason] = useState("");
  const [correctionConfirmation, setCorrectionConfirmation] = useState(false);
  const [closeReason, setCloseReason] = useState("");
  const [closeConfirmation, setCloseConfirmation] = useState(false);
  const availableResolutions = caseFile.sourceCategory === "RISK_REVIEW"
    ? riskResolutions
    : reconciliationResolutions;
  const [resolution, setResolution] = useState<string>(availableResolutions[0]);
  const [target, setTarget] = useState<CoreCase["status"]>(defaultTarget(caseFile.status));
  const [state, setState] = useState<State>({ kind: "idle" });

  async function submit(path: string, body: Record<string, unknown>, success: string, onSuccess: () => void) {
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
      onSuccess();
      router.refresh();
    } catch { setState({ kind: "error", message: "Operations Core could not be reached." }); }
  }

  if (readOnly) return <span className="table-secondary">Read-only support mode</span>;

  return (
    <div className="action-stack">
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/assignment`, { ownerId, reason: assignmentReason }, "Case assigned.", () => setAssignmentReason("")); }}>
        <label>Owner ID<input value={ownerId} onChange={(event) => setOwnerId(event.target.value)} required pattern="[0-9a-fA-F-]{36}" /></label>
        <label>Assignment reason<textarea value={assignmentReason} onChange={(event) => setAssignmentReason(event.target.value)} required maxLength={512} rows={2} /></label>
        <button type="submit" disabled={state.kind === "pending"}>Assign / reassign</button>
      </form>
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/transitions`, { target, reason: transitionReason, confirmation: transitionConfirmation }, "Case status updated.", () => { setTransitionReason(""); setTransitionConfirmation(false); }); }}>
        <label>Next status<select value={target} onChange={(event) => { setTarget(event.target.value as CoreCase["status"]); setTransitionConfirmation(false); }}>{transitions.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Transition reason<textarea value={transitionReason} onChange={(event) => setTransitionReason(event.target.value)} required maxLength={512} rows={2} /></label>
        {target === "REOPENED" && <label className="checkbox-label"><input type="checkbox" checked={transitionConfirmation} onChange={(event) => setTransitionConfirmation(event.target.checked)} required />I confirm this Case should be reopened.</label>}
        <button className="secondary" type="submit" disabled={state.kind === "pending" || (target === "REOPENED" && !transitionConfirmation)}>Change status</button>
      </form>
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/notes`, { note: investigationNote }, "Investigation note added.", () => setInvestigationNote("")); }}>
        <label>Investigation note<textarea value={investigationNote} onChange={(event) => setInvestigationNote(event.target.value)} required maxLength={4000} rows={3} /></label>
        <button className="secondary" type="submit" disabled={state.kind === "pending"}>Add note</button>
      </form>
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/resolution`, { resolution, note: resolutionNote, confirmation: resolutionConfirmation }, "Case resolved.", () => { setResolutionNote(""); setResolutionConfirmation(false); }); }}>
        <label>Resolution<select value={resolution} onChange={(event) => setResolution(event.target.value)}>{availableResolutions.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Resolution note<textarea value={resolutionNote} onChange={(event) => setResolutionNote(event.target.value)} required maxLength={4000} rows={3} /></label>
        <label className="checkbox-label"><input type="checkbox" checked={resolutionConfirmation} onChange={(event) => setResolutionConfirmation(event.target.checked)} required />I confirm this Case resolution.</label>
        <button type="submit" disabled={state.kind === "pending" || caseFile.status === "CLOSED" || !resolutionConfirmation}>Resolve case</button>
      </form>
      {caseFile.sourceCategory === "RECONCILIATION_DISCREPANCY" && caseFile.status === "INVESTIGATING" && !caseFile.correctiveActionCompleted && (
        <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/correction`, { settlementPostingId, originalLedgerTransactionId, reason: correctionReason, confirmation: correctionConfirmation }, "Correction completed or recorded.", () => { setSettlementPostingId(""); setOriginalLedgerTransactionId(""); setCorrectionReason(""); setCorrectionConfirmation(false); }); }}>
          <label>Settlement posting ID<input value={settlementPostingId} onChange={(event) => setSettlementPostingId(event.target.value)} required pattern="[0-9a-fA-F-]{36}" /></label>
          <label>Original Ledger transaction ID<input value={originalLedgerTransactionId} onChange={(event) => setOriginalLedgerTransactionId(event.target.value)} required pattern="[0-9a-fA-F-]{36}" /></label>
          <label>Correction reason<textarea value={correctionReason} onChange={(event) => setCorrectionReason(event.target.value)} required maxLength={2000} rows={2} /></label>
          <label className="checkbox-label"><input type="checkbox" checked={correctionConfirmation} onChange={(event) => setCorrectionConfirmation(event.target.checked)} required />I confirm this exact financial correction.</label>
          <button className="danger" type="submit" disabled={state.kind === "pending" || !correctionConfirmation}>Request exact correction</button>
        </form>
      )}
      <form className="form-grid" onSubmit={(event: FormEvent<HTMLFormElement>) => { event.preventDefault(); void submit(`/api/cases/${caseFile.caseId}/close`, { reason: closeReason, confirmation: closeConfirmation }, "Case closed.", () => { setCloseReason(""); setCloseConfirmation(false); }); }}>
        <label>Closure reason<textarea value={closeReason} onChange={(event) => setCloseReason(event.target.value)} required maxLength={512} rows={2} /></label>
        <label className="checkbox-label"><input type="checkbox" checked={closeConfirmation} onChange={(event) => setCloseConfirmation(event.target.checked)} required />I confirm this Case should be closed.</label>
        <button className="danger" type="submit" disabled={state.kind === "pending" || caseFile.status !== "RESOLVED" || !closeConfirmation}>Close case</button>
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
