"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

export function PaymentNoteForm({ paymentId, csrfToken, disabled }: { paymentId: string; csrfToken: string; disabled: boolean }) {
  const [content, setContent] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setMessage(null);
    try {
      const response = await fetch(`/api/payments/${encodeURIComponent(paymentId)}/notes`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify({ content }),
      });
      const body = await response.json().catch(() => ({})) as { login?: string };
      if (response.status === 401 && body.login) {
        window.location.href = body.login;
        return;
      }
      if (!response.ok) {
        setMessage(response.status === 403 ? "You are not authorized to add a Payment note." : "The note could not be added.");
        return;
      }
      setContent("");
      setMessage("Note added. Notes cannot be edited or deleted.");
      router.refresh();
    } catch {
      setMessage("The note action could not reach Operations Core.");
    } finally {
      setPending(false);
    }
  }

  if (disabled) return <p className="table-secondary">Support mode is read-only; note creation is unavailable.</p>;
  return (
    <form className="form-grid" onSubmit={submit}>
      <label htmlFor="payment-note">Add immutable note</label>
      <textarea id="payment-note" value={content} onChange={(event) => setContent(event.target.value)} maxLength={4000} rows={4} required />
      <div className="button-row"><button type="submit" disabled={pending}>{pending ? "Adding…" : "Add note"}</button></div>
      {message && <div className="action-feedback status" role="status">{message}</div>}
    </form>
  );
}
