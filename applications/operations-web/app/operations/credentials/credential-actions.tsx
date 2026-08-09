"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import type { CoreCredentialMetadata } from "../../../lib/core";

type ActionState = {
  kind: "idle" | "pending" | "success" | "error";
  message?: string;
  clientSecret?: string;
};

const idle: ActionState = { kind: "idle" };

export function CredentialCreateForm({ csrfToken }: { csrfToken: string }) {
  const [merchantId, setMerchantId] = useState("");
  const [label, setLabel] = useState("");
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<ActionState>(idle);
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState({ kind: "pending", message: "Provisioning credential…" });
    const result = await postAction("/api/credentials/create", csrfToken, {
      merchantId,
      label,
      reason,
      confirmation,
    });
    if (result.kind === "session") {
      window.location.href = result.login;
      return;
    }
    if (result.kind === "error") {
      setState({ kind: "error", message: result.message });
      return;
    }
    setState({
      kind: "success",
      message: "Credential created. Store this secret now; it will not be shown again.",
      clientSecret: result.body.clientSecret,
    });
    setConfirmation(false);
    router.refresh();
  }

  return (
    <section className="panel">
      <div className="eyebrow">Create</div>
      <h2>New sandbox credential</h2>
      <p>Core will verify the Merchant scope and disclose the secret only in this response.</p>
      <form className="form-grid" onSubmit={submit}>
        <label>
          Merchant ID
          <input value={merchantId} onChange={(event) => setMerchantId(event.target.value)} required pattern="[0-9a-fA-F-]{36}" />
        </label>
        <label>
          Label
          <input value={label} onChange={(event) => setLabel(event.target.value)} required maxLength={255} />
        </label>
        <label>
          Reason
          <textarea value={reason} onChange={(event) => setReason(event.target.value)} required maxLength={512} rows={3} />
        </label>
        <label className="checkbox-label">
          <input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} required />
          I confirm that this credential should be created.
        </label>
        <button type="submit" disabled={state.kind === "pending"}>Create credential</button>
      </form>
      <ActionFeedback state={state} />
    </section>
  );
}

export function CredentialRowActions({
  credential,
  csrfToken,
}: {
  credential: CoreCredentialMetadata;
  csrfToken: string;
}) {
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<ActionState>(idle);
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await submitAction("rotate");
  }

  async function submitAction(action: "rotate" | "revoke") {
    setState({ kind: "pending", message: `${action === "rotate" ? "Rotating" : "Revoking"} credential…` });
    const result = await postAction(`/api/credentials/${action}`, csrfToken, {
      credentialId: credential.credentialId,
      reason,
      confirmation,
    });
    if (result.kind === "session") {
      window.location.href = result.login;
      return;
    }
    if (result.kind === "error") {
      setState({ kind: "error", message: result.message });
      return;
    }
    setState({
      kind: "success",
      message: action === "rotate"
        ? "Replacement credential created. Store this secret now; it will not be shown again."
        : "Credential revoked locally.",
      clientSecret: result.body.clientSecret,
    });
    setConfirmation(false);
    router.refresh();
  }

  if (credential.status === "REVOKED") {
    return <span className="table-secondary">No further actions</span>;
  }

  return (
    <div className="action-stack">
      <form onSubmit={submit}>
        <label className="sr-only" htmlFor={`reason-${credential.credentialId}`}>Action reason</label>
        <textarea
          id={`reason-${credential.credentialId}`}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Reason"
          required
          maxLength={512}
          rows={2}
        />
        <label className="checkbox-label compact">
          <input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} required />
          Confirm
        </label>
        <div className="button-row">
          <button type="submit" disabled={state.kind === "pending" || credential.status !== "ACTIVE"}>Rotate</button>
          <button
            className="danger"
            type="button"
            onClick={(event) => {
              if (event.currentTarget.form?.reportValidity()) void submitAction("revoke");
            }}
            disabled={state.kind === "pending"}
          >
            Revoke
          </button>
        </div>
      </form>
      <ActionFeedback state={state} />
    </div>
  );
}

async function postAction(path: string, csrfToken: string, body: Record<string, unknown>) {
  try {
    const response = await fetch(path, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-csrf-token": csrfToken,
      },
      body: JSON.stringify(body),
    });
    const responseBody = await response.json().catch(() => ({})) as {
      type?: string;
      login?: string;
      clientSecret?: string;
    };
    if (response.status === 401 && responseBody.login) {
      return { kind: "session" as const, login: responseBody.login };
    }
    if (!response.ok) {
      return { kind: "error" as const, message: actionMessage(response.status, responseBody.type) };
    }
    return { kind: "ok" as const, body: responseBody };
  } catch {
    return { kind: "error" as const, message: "The action could not reach Operations Core." };
  }
}

function actionMessage(status: number, type?: string) {
  if (status === 403) return "You are not authorized to perform this action.";
  if (status === 404) return "The credential is outside your authorized scope.";
  if (status === 409) return "The credential state changed. Refresh and try again.";
  if (status === 503) return "External credential cleanup is unavailable. The local state was not silently changed.";
  if (type === "invalid_request") return "Enter a reason and confirm the action.";
  return "The credential action could not be completed.";
}

function ActionFeedback({ state }: { state: ActionState }) {
  if (state.kind === "idle") return null;
  return (
    <div className={`action-feedback ${state.kind}`} role="alert">
      <p>{state.message}</p>
      {state.clientSecret && (
        <>
          <p><strong>One-time client secret</strong></p>
          <code className="secret-value">{state.clientSecret}</code>
        </>
      )}
    </div>
  );
}
