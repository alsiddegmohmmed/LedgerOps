"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

export function PaymentRetryForm({ paymentId, csrfToken }: { paymentId: string; csrfToken: string }) {
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
      const response = await fetch(`/api/payments/${encodeURIComponent(paymentId)}/retry`, {
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
        setMessage(retryMessage(response.status, body.type));
        return;
      }
      setMessage("Retry work was accelerated. The Provider worker will process the existing work item.");
      setReason("");
      setConfirmation(false);
      router.refresh();
    } catch {
      setMessage("The retry action could not reach Operations Core.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className="form-grid" onSubmit={submit}>
      <label htmlFor="payment-retry-reason">Reason for retry acceleration</label>
      <textarea id="payment-retry-reason" value={reason} onChange={(event) => setReason(event.target.value)} maxLength={512} rows={3} required />
      <label className="checkbox-label">
        <input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} required />
        I confirm that an existing safe retry should run now.
      </label>
      <div className="button-row"><button type="submit" disabled={pending}>{pending ? "Accelerating…" : "Retry now"}</button></div>
      {message && <div className="action-feedback status" role="status">{message}</div>}
    </form>
  );
}

function retryMessage(status: number, type?: string) {
  if (status === 403) return "You are not authorized to request a Payment retry.";
  if (status === 404) return "The Payment is outside your authorized Merchant scope.";
  if (status === 409) return "No safe unapplied retry is available, or the Payment state changed. Refresh and inspect the evidence.";
  if (type === "invalid_request") return "Enter a reason and confirm the retry.";
  return "The Payment retry could not be requested.";
}
