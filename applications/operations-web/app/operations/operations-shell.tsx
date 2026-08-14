"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

type NavItem = {
  href: string;
  label: string;
};

type NavGroup = {
  label: string;
  items: NavItem[];
};

const NAV_GROUPS: NavGroup[] = [
  {
    label: "Workspace",
    items: [
      { href: "/operations", label: "Overview" },
      { href: "/operations/summary", label: "Operational summary" },
    ],
  },
  {
    label: "Operations",
    items: [
      { href: "/operations/payments", label: "Payments" },
      { href: "/operations/risk-reviews", label: "Risk reviews" },
      { href: "/operations/cases", label: "Cases" },
      { href: "/operations/reconciliation", label: "Reconciliation" },
      { href: "/operations/provider", label: "Provider" },
      { href: "/operations/ledger", label: "Ledger" },
      { href: "/operations/settlements", label: "Settlements" },
      { href: "/operations/audit", label: "Audit trail" },
    ],
  },
  {
    label: "Administration",
    items: [
      { href: "/operations/merchants", label: "Merchants" },
      { href: "/operations/memberships", label: "Memberships" },
      { href: "/operations/credentials", label: "Credentials" },
      { href: "/operations/configuration", label: "Tenant configuration" },
      { href: "/operations/risk-configuration", label: "Risk configuration" },
      { href: "/operations/contacts", label: "Operational contacts" },
      { href: "/operations/webhooks", label: "Merchant webhooks" },
      { href: "/operations/support", label: "Support console" },
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
  const tenantIsActive = tenant?.status === "ACTIVE";

  return (
    <div className="operations-app">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <span className="brand-mark" aria-hidden="true">L</span>
          <span>
            <strong>LedgerOps</strong>
            <small>Operations Web</small>
          </span>
        </div>

        <div className="workspace-switcher">
          <span className="sidebar-label">Active Tenant</span>
          <strong>{tenant?.name ?? "No Tenant selected"}</strong>
          <span className="workspace-status">
            <span className={`status-dot ${tenantIsActive ? "status-dot-live" : "status-dot-muted"}`} />
            {tenant ? tenant.status : "Select a Tenant on Overview"}
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
                      {item.label}
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
            <strong>LedgerOps</strong>
            <span className="topbar-separator" aria-hidden="true">/</span>
            <span>{tenant?.name ?? "Tenant selection"}</span>
          </div>
          <div className="topbar-status">
            {tenant && (
              <span className="tenant-status-inline">
                <span className={`status-dot ${tenantIsActive ? "status-dot-live" : "status-dot-muted"}`} />
                {tenant.status}
              </span>
            )}
            {supportActive && <span className="support-pill">Support mode</span>}
          </div>
        </header>
        <div className="app-content">{children}</div>
      </div>
    </div>
  );
}
