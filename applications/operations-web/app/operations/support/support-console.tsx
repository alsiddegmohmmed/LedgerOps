"use client";

import { FormEvent, useState } from "react";
import type { CoreMembership, CoreMerchant, CoreTenant } from "../../../lib/core";

type Props = {
  csrfToken: string;
  defaultTenantId?: string;
  active?: { tenantId: string; expiresAt: number };
  tenant?: CoreTenant | null;
  merchants?: CoreMerchant[];
  memberships?: CoreMembership[];
};

export function SupportConsole({
  csrfToken,
  defaultTenantId,
  active,
  tenant,
  merchants = [],
  memberships = [],
}: Props) {
  const [tenantId, setTenantId] = useState(defaultTenantId ?? active?.tenantId ?? "");
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [state, setState] = useState<"idle" | "pending" | "error">("idle");
  const [message, setMessage] = useState("");

  async function start(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setState("pending");
    setMessage("");
    try {
      const response = await fetch("/api/support/start", {
        method: "POST",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify({ tenantId, reason, confirmation }),
      });
      if (!response.ok) {
        setState("error");
        setMessage(response.status === 403
          ? "Only an eligible Platform Admin can enter support mode."
          : "Support mode could not be started.");
        return;
      }
      window.location.reload();
    } catch {
      setState("error");
      setMessage("The support request could not reach Operations Core.");
    }
  }

  if (!active) {
    return (
      <section className="panel">
        <form className="form-grid" onSubmit={start}>
          <label>
            Tenant ID
            <input
              value={tenantId}
              onChange={(event) => setTenantId(event.target.value)}
              pattern="[0-9a-fA-F-]{36}"
              required
            />
          </label>
          <label>
            Reason
            <textarea
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              maxLength={512}
              rows={4}
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
            I understand that support mode is read-only and audited.
          </label>
          <button type="submit" disabled={state === "pending"}>Enter support mode</button>
        </form>
        {state === "error" && <div className="action-feedback error" role="alert"><p>{message}</p></div>}
      </section>
    );
  }

  return (
    <>
      {state === "error" && <div className="action-feedback error" role="alert"><p>{message}</p></div>}
      <div className="eyebrow">Support console</div>
      <h1>Tenant read view</h1>
      {tenant ? (
        <div className="panel">
          <div className="eyebrow">Tenant read</div>
          <h2>{tenant.name}</h2>
          <p>{tenant.status} · {tenant.defaultCurrency} · {tenant.defaultLocale}</p>
        </div>
      ) : (
        <div className="panel status error">The Tenant could not be read through the active support session.</div>
      )}
      <div className="panel">
        <div className="eyebrow">Merchant read</div>
        {merchants.length === 0 ? <p>No Merchants are visible.</p> : (
          <ul>{merchants.map((merchant) => <li key={merchant.merchantId}>{merchant.name} · {merchant.status}</li>)}</ul>
        )}
      </div>
      <div className="panel">
        <div className="eyebrow">Membership read</div>
        {memberships.length === 0 ? <p>No memberships are visible.</p> : (
          <ul>{memberships.map((membership) => (
            <li key={membership.membershipId}>{membership.status} · {membership.identityLinked ? "Linked" : "Invitation pending"}</li>
          ))}</ul>
        )}
      </div>
    </>
  );
}
