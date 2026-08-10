"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { type CoreRiskReview } from "../../../lib/core";

type State = { kind: "idle" | "pending" | "success" | "error"; message?: string };

export function RiskReviewActions({
  review,
  csrfToken,
  readOnly,
}: {
  review: CoreRiskReview;
  csrfToken: string;
  readOnly: boolean;
}) {
  const router = useRouter();
  const [analystId, setAnalystId] = useState(review.assignedAnalystId ?? "");
  const [priority, setPriority] = useState(String(review.priority));
  const [assignmentReason, setAssignmentReason] = useState("");
  const [decision, setDecision] = useState<"APPROVE" | "REJECT" | "ESCALATE">("APPROVE");
  const [decisionReason, setDecisionReason] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });

  async function assign(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await post(`/api/risk-reviews/${review.reviewId}/assignment`, {
      analystId, priority: Number(priority), reason: assignmentReason,
    });
  }

  async function decide(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await post(`/api/risk-reviews/${review.reviewId}/decisions`, {
      decision, reason: decisionReason,
    });
  }

  async function post(path: string, body: Record<string, unknown>) {
    setState({ kind: "pending", message: "Saving risk review…" });
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify(body),
      });
      const payload = await response.json().catch(() => ({})) as { type?: string; login?: string };
      if (response.status === 401 && payload.login) { window.location.href = payload.login; return; }
      if (!response.ok) {
        setState({ kind: "error", message: response.status === 403 ? "You are not authorized for this action." : "The risk review changed or the action was rejected." });
        return;
      }
      setState({ kind: "success", message: "Risk review updated." });
      setAssignmentReason(""); setDecisionReason(""); router.refresh();
    } catch { setState({ kind: "error", message: "Operations Core could not be reached." }); }
  }

  if (readOnly || review.status === "DECIDED" || review.status === "ESCALATED") {
    return <span className="table-secondary">{readOnly ? "Read-only support mode" : "Final decision recorded"}</span>;
  }

  return (
    <div className="action-stack">
      <form className="form-grid" onSubmit={assign}>
        <label>Analyst ID<input value={analystId} onChange={(event) => setAnalystId(event.target.value)} required pattern="[0-9a-fA-F-]{36}" /></label>
        <label>Priority<input type="number" min="0" value={priority} onChange={(event) => setPriority(event.target.value)} required /></label>
        <label>Assignment reason<textarea value={assignmentReason} onChange={(event) => setAssignmentReason(event.target.value)} required maxLength={512} rows={2} /></label>
        <button type="submit" disabled={state.kind === "pending"}>Assign / reassign</button>
      </form>
      <form className="form-grid" onSubmit={decide}>
        <label>Decision<select value={decision} onChange={(event) => setDecision(event.target.value as typeof decision)}><option>APPROVE</option><option>REJECT</option><option>ESCALATE</option></select></label>
        <label>Decision reason<textarea value={decisionReason} onChange={(event) => setDecisionReason(event.target.value)} required maxLength={2000} rows={2} /></label>
        <button className="secondary" type="submit" disabled={state.kind === "pending" || !review.assignedAnalystId}>Decide</button>
      </form>
      {state.kind !== "idle" && <div className={`action-feedback ${state.kind}`} role="alert"><p>{state.message}</p></div>}
    </div>
  );
}
