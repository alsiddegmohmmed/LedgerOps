"use client";

import { useState } from "react";

export function SupportModeBanner({ csrfToken, expiresAt }: { csrfToken: string; expiresAt: number }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);

  async function exit() {
    setPending(true);
    setError(false);
    try {
      const response = await fetch("/api/support/exit", {
        method: "POST",
        headers: { "x-csrf-token": csrfToken },
      });
      if (!response.ok) {
        setError(true);
        setPending(false);
        return;
      }
      window.location.href = "/operations";
    } catch {
      setError(true);
      setPending(false);
    }
  }

  return (
    <div className="support-banner" role="status">
      <div>
        <div className="eyebrow">Support mode active</div>
        <strong>READ-ONLY ACCESS</strong>
        <p>Every read is audited. Business mutations are unavailable.</p>
        <p>Expires {new Date(expiresAt).toLocaleString()}</p>
        {error && <p role="alert">Support mode could not be exited.</p>}
      </div>
      <button className="danger" type="button" onClick={exit} disabled={pending}>
        Exit support mode
      </button>
    </div>
  );
}
