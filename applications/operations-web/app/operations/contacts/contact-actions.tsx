"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import type { CoreOperationalContact } from "../../../lib/core";

type ActionState = {
  kind: "idle" | "pending" | "success" | "error";
  message?: string;
};

export function OperationalContactForm({
  contact,
  csrfToken,
}: {
  contact?: CoreOperationalContact;
  csrfToken: string;
}) {
  const [displayName, setDisplayName] = useState(contact?.displayName ?? "");
  const [email, setEmail] = useState(contact?.email ?? "");
  const [purpose, setPurpose] = useState(contact?.purpose ?? "");
  const [active, setActive] = useState(contact?.active ?? true);
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<ActionState>({ kind: "idle" });
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState({ kind: "pending", message: "Saving operational contact…" });
    try {
      const response = await fetch("/api/tenant/operational-contacts", {
        method: "PUT",
        headers: {
          "content-type": "application/json",
          "x-csrf-token": csrfToken,
        },
        body: JSON.stringify({
          contactId: contact?.contactId ?? crypto.randomUUID(),
          displayName,
          email,
          purpose,
          active,
          reason,
          confirmation,
        }),
      });
      const body = await response.json().catch(() => ({})) as {
        type?: string;
        login?: string;
        version?: number;
      };
      if (response.status === 401 && body.login) {
        window.location.href = body.login;
        return;
      }
      if (!response.ok) {
        setState({ kind: "error", message: contactMessage(response.status, body.type) });
        return;
      }
      setState({ kind: "success", message: `Contact saved as version ${body.version ?? "new"}.` });
      setConfirmation(false);
      setReason("");
      router.refresh();
    } catch {
      setState({ kind: "error", message: "The contact update could not reach Operations Core." });
    }
  }

  return (
    <section className="panel">
      <div className="eyebrow">{contact ? `Version ${contact.version}` : "New contact"}</div>
      <h2>{contact ? contact.displayName : "Add operational contact"}</h2>
      <form className="form-grid" onSubmit={submit}>
        <label>
          Display name
          <input
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            maxLength={160}
            required
          />
        </label>
        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            maxLength={320}
            required
          />
        </label>
        <label>
          Purpose
          <input
            value={purpose}
            onChange={(event) => setPurpose(event.target.value)}
            maxLength={120}
            placeholder="settlement"
            required
          />
        </label>
        <label className="checkbox-label">
          <input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} />
          Contact is active
        </label>
        <label>
          Reason
          <textarea
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={512}
            rows={3}
            required
          />
        </label>
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={confirmation}
            onChange={(event) => setConfirmation(event.target.checked)}
            required
          />
          I confirm this operational-contact change.
        </label>
        <button type="submit" disabled={state.kind === "pending"}>Save contact</button>
      </form>
      {state.kind !== "idle" && (
        <div className={`action-feedback ${state.kind}`} role="alert">
          <p>{state.message}</p>
        </div>
      )}
    </section>
  );
}

function contactMessage(status: number, type?: string) {
  if (status === 403) return "You are not authorized to change operational contacts.";
  if (status === 404) return "This Tenant or contact is outside your authorized scope.";
  if (status === 409) return "The contact or Tenant state changed. Refresh and try again.";
  if (status === 400 || type === "invalid_request") return "Enter valid contact details, a reason, and confirm the change.";
  if (status === 502 || status === 503) return "Operations Core is unavailable right now.";
  return "The operational contact could not be updated.";
}
