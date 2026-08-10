"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

export function ReversalRequestForm({ paymentId, csrfToken }: { paymentId: string; csrfToken: string }) {
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
      const response = await fetch(`/api/payments/${encodeURIComponent(paymentId)}/reversal`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify({ reason, confirmation }),
      });
      const body = await response.json().catch(() => ({})) as { login?: string; type?: string };
      if (response.status === 401 && body.login) {
        window.location.href = body.login;
        return;
      }
      if (!response.ok) {
        setMessage(reversalMessage(response.status, body.type));
        return;
      }
      setMessage("The Reversal was requested. Provider processing will continue asynchronously.");
      setReason("");
      setConfirmation(false);
      router.refresh();
    } catch {
      setMessage("The Reversal request could not reach Operations Core.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="form-grid" onSubmit={submit}>
      <label htmlFor="reversal-request-reason">Reason for full Reversal</label>
      <textarea id="reversal-request-reason" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={512} rows={3} required />
      <label className="checkbox-label">
        <input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} required />
        I confirm that the full completed Payment should be reversed.
      </label>
      <div className="button-row"><button type="submit" disabled={pending}>{pending ? "Requesting…" : "Request full Reversal"}</button></div>
      {message && <div className="action-feedback status" role="status">{message}</div>}
    </form>
  );
}

function reversalMessage(status: number, type?: string) {
  if (status === 403) return "You are not authorized to request a Reversal.";
  if (status === 404) return "The Payment is outside your authorized Merchant scope.";
  if (status === 409) return "A Reversal already exists or the Payment state changed. Refresh the detail view.";
  if (type === "invalid_request") return "Enter a reason and confirm the full Reversal.";
  return "The Reversal request could not be completed.";
}
