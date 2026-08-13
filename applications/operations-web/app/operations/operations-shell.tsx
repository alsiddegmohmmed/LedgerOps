"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

type NavItem = {
  href: string;
  label: string;
  glyph: string;
};

type NavGroup = {
  label: string;
  items: NavItem[];
};

const NAV_GROUPS: NavGroup[] = [
  {
    label: "Workspace",
    items: [
      { href: "/operations", label: "Overview", glyph: "⌂" },
      { href: "/operations/summary", label: "Operational summary", glyph: "▥" },
    ],
  },
  {
    label: "Work queues",
    items: [
      { href: "/operations/payments", label: "Payments", glyph: "↗" },
      { href: "/operations/risk-reviews", label: "Risk reviews", glyph: "!" },
      { href: "/operations/cases", label: "Cases", glyph: "◇" },
      { href: "/operations/reconciliation", label: "Reconciliation", glyph: "≋" },
      { href: "/operations/settlements", label: "Settlements", glyph: "⇄" },
    ],
  },
  {
    label: "Control room",
    items: [
      { href: "/operations/provider", label: "Provider health", glyph: "◉" },
      { href: "/operations/audit", label: "Audit trail", glyph: "◫" },
      { href: "/operations/ledger", label: "Ledger", glyph: "⊞" },
      { href: "/operations/settlements", label: "Settlement ingestion", glyph: "⇄" },
    ],
  },
  {
    label: "Administration",
    items: [
      { href: "/operations/merchants", label: "Merchants", glyph: "•" },
      { href: "/operations/memberships", label: "Memberships", glyph: "◎" },
      { href: "/operations/credentials", label: "Credentials", glyph: "⌁" },
      { href: "/operations/configuration", label: "Tenant configuration", glyph: "⚙" },
      { href: "/operations/risk-configuration", label: "Risk configuration", glyph: "◇" },
      { href: "/operations/contacts", label: "Operational contacts", glyph: "•" },
      { href: "/operations/webhooks", label: "Merchant webhooks", glyph: "⌁" },
      { href: "/operations/support", label: "Support console", glyph: "◎" },
    ],
  },
];

export function OperationsShell({
  children,
  csrfToken,
  tenant,
  supportActive,
}: {
  children: ReactNode;
  csrfToken?: string;
  tenant: { id: string; name: string; status: string } | null;
  supportActive: boolean;
}) {
  const pathname = usePathname();

  return (
    <div className="operations-app">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <span className="brand-mark" aria-hidden="true">L</span>
          <span>
            <strong>LedgerOps</strong>
            <small>Operations</small>
          </span>
        </div>

        <div className="workspace-switcher">
          <span className="sidebar-label">Active workspace</span>
          <strong>{tenant?.name ?? "No Tenant selected"}</strong>
          <span className="workspace-status">
            <span className={`status-dot ${tenant ? "status-dot-live" : "status-dot-muted"}`} />
            {tenant ? `${tenant.status} · ${tenant.id.slice(0, 8)}` : "Choose a Tenant to begin"}
          </span>
        </div>

        <nav className="sidebar-nav" aria-label="Operations navigation">
          {NAV_GROUPS.map((group) => (
            <div className="nav-group" key={group.label}>
              <span className="sidebar-label">{group.label}</span>
              <div className="nav-list">
                {group.items.map((item) => {
                  const active = item.href === "/operations"
                    ? pathname === item.href
                    : pathname === item.href || pathname.startsWith(`${item.href}/`);
                  return (
                    <Link
                      className={`nav-item${active ? " nav-item-active" : ""}`}
                      href={item.href}
                      key={item.href}
                      aria-current={active ? "page" : undefined}
                    >
                      <span className="nav-glyph" aria-hidden="true">{item.glyph}</span>
                      <span>{item.label}</span>
                    </Link>
                  );
                })}
              </div>
            </div>
          ))}
        </nav>

        <div className="sidebar-footer">
          {supportActive && <span className="support-chip">Support · read-only</span>}
          {csrfToken && (
            <form action="/api/auth/logout" method="post">
              <input type="hidden" name="csrfToken" value={csrfToken} />
              <button className="sidebar-logout" type="submit">Sign out</button>
            </form>
          )}
        </div>
      </aside>

      <div className="app-viewport">
        <header className="topbar">
          <div className="topbar-context">
            <span className="topbar-kicker">Operations workspace</span>
            <span className="topbar-separator" aria-hidden="true">/</span>
            <span>{tenant?.name ?? "Tenant selection"}</span>
          </div>
          <div className="topbar-status">
            <span className="connection-pill"><span className="status-dot status-dot-live" /> Core connected</span>
            {supportActive && <span className="support-pill">Support mode</span>}
          </div>
        </header>
        <div className="app-content">{children}</div>
      </div>
    </div>
  );
}
