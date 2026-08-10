"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import type { CoreRiskConfiguration, CoreRiskRuleConfiguration } from "../../../lib/core";

export function RiskConfigurationForm({ configuration, csrfToken }: { configuration: CoreRiskConfiguration; csrfToken: string }) {
  const [reviewThreshold, setReviewThreshold] = useState(String(configuration.reviewThreshold));
  const [rejectThreshold, setRejectThreshold] = useState(String(configuration.rejectThreshold));
  const [rules, setRules] = useState(JSON.stringify(configuration.rules, null, 2));
  const [reason, setReason] = useState("");
  const [confirmation, setConfirmation] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const router = useRouter();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    let parsed: CoreRiskRuleConfiguration[];
    try {
      parsed = JSON.parse(rules) as CoreRiskRuleConfiguration[];
      if (!Array.isArray(parsed)) throw new Error("Rules must be an array");
    } catch {
      setMessage("Rules must be a valid JSON array.");
      return;
    }
    setPending(true);
    setMessage(null);
    try {
      const response = await fetch("/api/risk/configuration", {
        method: "PUT",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify({
          reviewThreshold: Number(reviewThreshold),
          rejectThreshold: Number(rejectThreshold),
          rules: parsed,
          expectedVersion: configuration.version,
          reason,
          confirmation,
        }),
      });
      const body = await response.json().catch(() => ({})) as { login?: string; type?: string };
      if (response.status === 401 && body.login) {
        window.location.href = body.login;
        return;
      }
      if (!response.ok) {
        setMessage(response.status === 403 ? "Tenant-wide Risk configuration authority is required." : response.status === 409 ? "The Risk configuration changed. Refresh and try again." : `Risk configuration update failed (${body.type ?? response.status}).`);
        return;
      }
      setMessage("Risk configuration version created and audited.");
      setConfirmation(false);
      setReason("");
      router.refresh();
    } catch {
      setMessage("The Risk configuration update could not reach Operations Core.");
    } finally {
      setPending(false);
    }
  }

  return <section className="panel"><div className="eyebrow">Tenant-wide management</div><h2>Active version {configuration.version}</h2><p>Each update creates a new version. Evaluation evidence keeps its original profile and version.</p><form className="form-grid" onSubmit={submit}><label>Review threshold<input type="number" min="1" max="99" value={reviewThreshold} onChange={(event) => setReviewThreshold(event.target.value)} required /></label><label>Reject threshold<input type="number" min="2" max="100" value={rejectThreshold} onChange={(event) => setRejectThreshold(event.target.value)} required /></label><label>Rules JSON<textarea value={rules} onChange={(event) => setRules(event.target.value)} rows={12} required /></label><label>Reason<textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={512} rows={3} required /></label><label className="checkbox-label"><input type="checkbox" checked={confirmation} onChange={(event) => setConfirmation(event.target.checked)} required />I confirm this Tenant-wide Risk configuration change.</label><button type="submit" disabled={pending}>{pending ? "Saving…" : "Create configuration version"}</button></form>{message && <div className="action-feedback status" role="status">{message}</div>}</section>;
}
