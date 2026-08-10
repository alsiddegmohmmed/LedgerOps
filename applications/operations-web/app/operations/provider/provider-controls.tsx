"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";

const outcomes = [
  "SUCCESS", "DECLINE", "ACCEPTED", "PENDING", "TIMEOUT_AFTER_ACCEPTANCE",
  "TEMPORARY_FAILURE_SAFE_TO_RESUBMIT", "TEMPORARY_FAILURE_STATUS_RECOVERY",
];
const webhookModes = ["NORMAL", "DELAYED", "DUPLICATE", "MISSING", "INVALID_SIGNATURE", "OUT_OF_ORDER"];
const settlementModes = ["EXACT", "MISSING", "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "STATUS_MISMATCH", "DUPLICATE_RECORD", "DATE_MISMATCH"];

export function ProviderScenarioControls({ csrfToken, tenantId }: { csrfToken: string; tenantId: string }) {
  const [profile, setProfile] = useState({
    profileId: "",
    expectedPreviousVersion: "",
    submissionOutcome: "SUCCESS",
    webhookMode: "NORMAL",
    settlementMode: "EXACT",
    delayMillis: "0",
    fixtureId: "",
  });
  const [assignment, setAssignment] = useState({
    scope: "TENANT",
    tenantId,
    paymentId: "",
    profileId: "",
    profileVersion: "1",
  });
  const [message, setMessage] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const router = useRouter();

  async function submitProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await submit("/api/provider/scenarios/profile", {
      ...profile,
      delayMillis: Number(profile.delayMillis),
      profileId: profile.profileId || null,
      expectedPreviousVersion: profile.expectedPreviousVersion || null,
      fixtureId: profile.fixtureId || null,
      confirmation: true,
    }, "Scenario profile version created.");
  }

  async function submitAssignment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await submit("/api/provider/scenarios/assignment", {
      ...assignment,
      tenantId: assignment.scope === "GLOBAL" ? null : assignment.tenantId || null,
      paymentId: assignment.scope === "PAYMENT" ? assignment.paymentId || null : null,
      profileVersion: Number(assignment.profileVersion),
      confirmation: true,
    }, "Scenario assignment created for future simulator behavior.");
  }

  async function submit(path: string, body: Record<string, unknown>, success: string) {
    setPending(true);
    setMessage(null);
    try {
      const response = await fetch(path, {
        method: "POST",
        headers: { "content-type": "application/json", "x-csrf-token": csrfToken },
        body: JSON.stringify(body),
      });
      const responseBody = await response.json().catch(() => ({})) as { login?: string; type?: string };
      if (response.status === 401 && responseBody.login) {
        window.location.href = responseBody.login;
        return;
      }
      if (!response.ok) {
        setMessage(response.status === 403 ? "Platform Admin authority is required for Provider scenario controls." : `Provider scenario action failed (${responseBody.type ?? response.status}).`);
        return;
      }
      setMessage(success);
      router.refresh();
    } catch {
      setMessage("The Provider scenario action could not reach Operations Core.");
    } finally {
      setPending(false);
    }
  }

  return (
    <section className="panel">
      <div className="eyebrow">Platform Admin controls</div>
      <h2>Create immutable scenario profile</h2>
      <p>Profiles are versioned. Assignments affect future simulator behavior; first-attempt pins remain unchanged.</p>
      <form className="form-grid" onSubmit={submitProfile}>
        <label>Existing profile ID for a new version<input value={profile.profileId} onChange={(event) => setProfile({ ...profile, profileId: event.target.value })} placeholder="Optional UUID" /></label>
        <label>Expected previous version<input type="number" min="1" value={profile.expectedPreviousVersion} onChange={(event) => setProfile({ ...profile, expectedPreviousVersion: event.target.value })} placeholder="Required when versioning" /></label>
        <label>Submission outcome<select value={profile.submissionOutcome} onChange={(event) => setProfile({ ...profile, submissionOutcome: event.target.value })}>{outcomes.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Webhook mode<select value={profile.webhookMode} onChange={(event) => setProfile({ ...profile, webhookMode: event.target.value })}>{webhookModes.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Settlement mode<select value={profile.settlementMode} onChange={(event) => setProfile({ ...profile, settlementMode: event.target.value })}>{settlementModes.map((value) => <option key={value}>{value}</option>)}</select></label>
        <label>Delay (milliseconds)<input type="number" min="0" max="300000" value={profile.delayMillis} onChange={(event) => setProfile({ ...profile, delayMillis: event.target.value })} /></label>
        <label>Fixture ID<input value={profile.fixtureId} onChange={(event) => setProfile({ ...profile, fixtureId: event.target.value })} placeholder="Optional" /></label>
        <button type="submit" disabled={pending}>Create profile version</button>
      </form>
      <h2>Assign profile</h2>
      <form className="form-grid" onSubmit={submitAssignment}>
        <label>Scope<select value={assignment.scope} onChange={(event) => setAssignment({ ...assignment, scope: event.target.value })}><option>GLOBAL</option><option>TENANT</option><option>PAYMENT</option></select></label>
        <label>Tenant ID<input value={assignment.tenantId} onChange={(event) => setAssignment({ ...assignment, tenantId: event.target.value })} placeholder="Required for TENANT and PAYMENT" /></label>
        <label>Payment ID<input value={assignment.paymentId} onChange={(event) => setAssignment({ ...assignment, paymentId: event.target.value })} placeholder="Required for PAYMENT" /></label>
        <label>Profile ID<input value={assignment.profileId} onChange={(event) => setAssignment({ ...assignment, profileId: event.target.value })} required /></label>
        <label>Profile version<input type="number" min="1" value={assignment.profileVersion} onChange={(event) => setAssignment({ ...assignment, profileVersion: event.target.value })} required /></label>
        <button type="submit" disabled={pending}>Assign profile</button>
      </form>
      {message && <div className="action-feedback status" role="status">{message}</div>}
    </section>
  );
}
