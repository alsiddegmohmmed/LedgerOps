import Link from "next/link";

export default function HomePage() {
  return (
    <main>
      <section className="shell">
        <div className="eyebrow">LedgerOps / Operations Web</div>
        <h1>Secure operational access.</h1>
        <p>
          Sign in through the local Keycloak realm, choose a Tenant, and let Core
          PostgreSQL revalidate the selected context on every request.
        </p>
        <Link className="button" href="/api/auth/login">Sign in</Link>
      </section>
    </main>
  );
}
