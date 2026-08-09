"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import type { CoreMembership } from "../../../lib/core";

type ActionState = {
  kind: "idle" | "pending" | "success" | "error";
  message?: string;
};

const idle: ActionState = { kind: "idle" };

export function InvitationRevokeAction({
  membership,
  csrfToken,
}: {
  membership: CoreMembership;
  csrfToken: string;
}) {
  const invitation = membership.invitation;
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<ActionState>(idle);
  const router = useRouter();

  if (membership.status !== "INVITED" || invitation?.status !== "PENDING") {
    return <span className="table-secondary">No pending invitation action</span>;
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState({ kind: "pending", message: "Revoking invitation…" });
    try {
      const response = await fetch("/api/memberships/revoke-invitation", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-csrf-token": csrfToken,
        },
        body: JSON.stringify({
          membershipId: membership.membershipId,
          reason,
          confirmation,
        }),
      });
      const body = await response.json().catch(() => ({})) as {
        type?: string;
        login?: string;
      };
      if (response.status === 401 && body.login) {
        window.location.href = body.login;
        return;
      }
      if (!response.ok) {
        setState({ kind: "error", message: actionMessage(response.status, body.type) });
        return;
      }
      setState({ kind: "success", message: "Invitation revoked locally." });
      setConfirmation(false);
      router.refresh();
    } catch {
      setState({ kind: "error", message: "The action could not reach Operations Core." });
    }
  }

  return (
    <div className="action-stack">
      <form className="form-grid" onSubmit={submit}>
        <label className="sr-only" htmlFor={`invitation-reason-${membership.membershipId}`}>
          Invitation revocation reason
        </label>
        <textarea
          id={`invitation-reason-${membership.membershipId}`}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Reason"
          required
          maxLength={512}
          rows={2}
        />
        <label className="checkbox-label compact">
          <input
            type="checkbox"
            checked={confirmation}
            onChange={(event) => setConfirmation(event.target.checked)}
            required
          />
          Confirm revocation
        </label>
        <button className="danger" type="submit" disabled={state.kind === "pending"}>
          Revoke invitation
        </button>
      </form>
      {state.kind !== "idle" && (
        <div className={`action-feedback ${state.kind}`} role="alert">
          <p>{state.message}</p>
        </div>
      )}
    </div>
  );
}

function actionMessage(status: number, type?: string) {
  if (status === 403) return "You are not authorized to revoke this invitation.";
  if (status === 404) return "The invitation is outside your authorized scope.";
  if (status === 409) return "The invitation state changed. Refresh and try again.";
  if (type === "invalid_request") return "Enter a reason and confirm the revocation.";
  return "The invitation could not be revoked.";
}
