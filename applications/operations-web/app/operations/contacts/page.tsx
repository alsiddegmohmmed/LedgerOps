import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getOperationalContacts, getTenant } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, readSession, SESSION_COOKIE } from "../../../lib/session";
import { OperationalContactForm } from "./contact-actions";

export const dynamic = "force-dynamic";

export default async function OperationalContactsPage() {
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
            Choose and verify a Tenant before opening operational contacts.
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

  const contactsResult = await getOperationalContacts(
    session.selectedTenantId,
    session.accessToken,
  );
  if (contactsResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (contactsResult.kind !== "ok") {
    return (
      <main>
        <section className="shell">
          <a href="/operations">← Operations</a>
          <div className="panel status error">
            You cannot read operational contacts for this Tenant right now.
          </div>
        </section>
      </main>
    );
  }

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Administration / Operational contacts</div>
        <h1>Operational contacts</h1>
        <p>{tenantResult.tenant.name}. Contact changes are versioned and audited by Core.</p>
        {contactsResult.contacts.length === 0 && (
          <div className="panel status">
            No operational contacts exist yet. Create the first one below.
          </div>
        )}
        {contactsResult.contacts.map((contact) => (
          <OperationalContactForm
            key={contact.contactId}
            contact={contact}
            csrfToken={session.csrfToken}
          />
        ))}
        <OperationalContactForm csrfToken={session.csrfToken} />
      </section>
    </main>
  );
}
