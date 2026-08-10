import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getMemberships, getTenant } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { InvitationRevokeAction } from "./invitation-revoke-action";

export const dynamic = "force-dynamic";

export default async function MembershipsPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  if (!session.selectedTenantId) {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            Choose and verify a Tenant before opening memberships.
          </div>
        </section>
      </main>
    );
  }

  const supportActive = isSupportSessionActive(session);
  const supportOptions = supportActive
    ? { supportSessionId: session.supportSessionId }
    : {};
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken, supportOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            This Tenant is unavailable or Core could not verify it right now.
          </div>
        </section>
      </main>
    );
  }

  const membershipsResult = await getMemberships(session.selectedTenantId, session.accessToken, supportOptions);
  if (membershipsResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (membershipsResult.kind !== "ok") {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            You cannot read memberships for this Tenant right now.
          </div>
        </section>
      </main>
    );
  }

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Administration / Memberships</div>
        <h1>Memberships</h1>
        <p>{tenantResult.tenant.name}. Results are filtered by your effective Tenant or Merchant scope.</p>
        <p className="status">Invitation secrets are never displayed.</p>
        {membershipsResult.memberships.length === 0 ? (
          <div className="panel status">No memberships are visible in the selected Tenant scope.</div>
        ) : (
          <div className="panel">
            <table>
              <caption className="sr-only">Visible Tenant memberships</caption>
              <thead>
                <tr>
                  <th scope="col">Status</th>
                  <th scope="col">Identity</th>
                  <th scope="col">Roles and scopes</th>
                  <th scope="col">Invitation</th>
                  <th scope="col">Membership ID</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {membershipsResult.memberships.map((membership) => (
                  <tr key={membership.membershipId}>
                    <th scope="row">{membership.status}</th>
                    <td>{membership.identityLinked ? "Linked" : "Invitation pending"}</td>
                    <td>
                      {membership.roleAssignments.length === 0 ? (
                        "No active role assignments"
                      ) : (
                        <ul>
                          {membership.roleAssignments.map((assignment) => (
                            <li key={assignment.assignmentId}>
                              {assignment.role} · {assignment.scopeMode}
                              {assignment.merchantIds.length > 0
                                ? ` · ${assignment.merchantIds.length} Merchant(s)`
                                : ""}
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                    <td>
                      {membership.invitation ? (
                        <>
                          <div>{membership.invitation.intendedEmail}</div>
                          <div>{membership.invitation.status}</div>
                        </>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="monospace">{membership.membershipId}</td>
                    <td>
                      {supportActive ? (
                        <span className="table-secondary">Unavailable in read-only support mode</span>
                      ) : (
                        <InvitationRevokeAction
                          membership={membership}
                          csrfToken={session.csrfToken}
                        />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </main>
  );
}
