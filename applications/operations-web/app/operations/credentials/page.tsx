import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getCredentialPage, getTenant, type CoreCredentialPage } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../../lib/session";
import { CredentialCreateForm, CredentialRowActions } from "./credential-actions";

export const dynamic = "force-dynamic";

type CredentialsPageProps = {
  searchParams: Promise<{ cursor?: string }>;
};

export default async function CredentialsPage({ searchParams }: CredentialsPageProps) {
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
            Choose and verify a Tenant before opening credentials.
          </div>
        </section>
      </main>
    );
  }

  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken);
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

  const { cursor } = await searchParams;
  const credentialResult = await getCredentialPage(
    session.selectedTenantId,
    session.accessToken,
    { cursor },
  );
  if (credentialResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Administration / Credentials</div>
        <h1>{tenantResult.tenant.name}</h1>
        <p>Non-secret credential metadata. Client secrets are never shown here.</p>
        {credentialResult.kind === "unavailable" && (
          <div className="panel status error">You cannot read credentials for this Tenant.</div>
        )}
        {credentialResult.kind === "error" && (
          <div className="panel status error">Core could not load credentials right now.</div>
        )}
        {credentialResult.kind === "ok" && (
          <>
            <CredentialCreateForm csrfToken={session.csrfToken} />
            <CredentialTable page={credentialResult.page} csrfToken={session.csrfToken} />
          </>
        )}
      </section>
    </main>
  );
}

function CredentialTable({ page, csrfToken }: { page: CoreCredentialPage; csrfToken: string }) {
  return (
    <div className="panel">
      {page.items.length === 0 ? (
        <p>No credentials found for this Tenant.</p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th scope="col">Label</th>
                <th scope="col">Status</th>
                <th scope="col">Merchant</th>
                <th scope="col">Created</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {page.items.map((credential) => (
                <tr key={credential.credentialId}>
                  <td>
                    <strong>{credential.label}</strong>
                    <span className="table-secondary">{credential.keycloakClientId}</span>
                  </td>
                  <td><span className="status-badge">{credential.status}</span></td>
                  <td className="monospace">{credential.merchantId}</td>
                  <td>{new Date(credential.createdAt).toLocaleString()}</td>
                  <td><CredentialRowActions credential={credential} csrfToken={csrfToken} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {page.nextCursor && (
        <a className="button" href={`/operations/credentials?cursor=${encodeURIComponent(page.nextCursor)}`}>
          Next page
        </a>
      )}
    </div>
  );
}
