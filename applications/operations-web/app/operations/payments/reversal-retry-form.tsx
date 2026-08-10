"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

export function ReversalRetryForm({
  reversalId,
  paymentId,
  previousAttemptId,
  providerEvidenceId,
  csrfToken,
}: {
  reversalId: string;
  paymentId: string;
  previousAttemptId: string;
  providerEvidenceId: string;
  csrfToken: string;
}) {
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setMessage(null);
    try {
      const response = await fetch(`/api/reversals/${encodeURIComponent(reversalId)}/retry`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify({ paymentId, previousAttemptId, providerEvidenceId, reason, confirmation }),
      });
      const body = await response.json().catch(() => ({})) as { login?: string; type?: string };
      if (response.status === 401 && body.login) {
        window.location.href = body.login;
        return;
      }
      if (!response.ok) {
        setMessage(retryMessage(response.status, body.type));
        return;
      }
      setMessage("The authorised Reversal retry was queued. No Provider call was made by this action.");
      setReason("");
      setConfirmation(false);
      router.refresh();
    } catch {
      setMessage("The Reversal retry could not reach Operations Core.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="form-grid" onSubmit={submit}>
      <label htmlFor="reversal-retry-reason">Reason for safe retry</label>
      <textarea id="reversal-retry-reason" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={512} rows={3} required />
      <label className="checkbox-label">
        <input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} required />
        I confirm that the latest durable evidence proves resubmission is safe.
      </label>
      <div className="button-row"><button type="submit" disabled={pending}>{pending ? "Retrying…" : "Retry Reversal"}</button></div>
      {message && <div className="action-feedback status" role="status">{message}</div>}
    </form>
  );
}

function retryMessage(status: number, type?: string) {
  if (status === 403) return "You are not authorized to retry a Reversal.";
  if (status === 404) return "The Reversal is outside your authorized Merchant scope.";
  if (status === 409) return "No safe unapplied retry is available, or the Reversal state changed. Refresh and inspect the evidence.";
  if (type === "invalid_request") return "Enter a reason and confirm the safe retry.";
  return "The Reversal retry could not be requested.";
}
