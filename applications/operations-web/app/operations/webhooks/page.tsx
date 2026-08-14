import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getMerchants, getTenant, getTenantConfiguration, getWebhookDeliveries, getWebhookEndpoints, type CoreMerchant, type CoreWebhookDelivery, type CoreWebhookEndpoint } from "../../../lib/core";
import { DEFAULT_OPERATIONS_TIMEZONE, formatOperationsDateTime } from "../../../lib/formatting";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { WebhookCreateForm, WebhookEndpointActions } from "./webhook-actions";

export const dynamic = "force-dynamic";

type MerchantWebhooks = { merchant: CoreMerchant; endpoints: CoreWebhookEndpoint[]; deliveries: Record<string, CoreWebhookDelivery[]> };

export default async function WebhooksPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");
  if (!session.selectedTenantId) return <MissingTenant />;

  const supportActive = isSupportSessionActive(session);
  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(session.selectedTenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Unavailable />;
  const configurationResult = await getTenantConfiguration(session.selectedTenantId, session.accessToken, readOptions);
  if (configurationResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  const displayLocale = configurationResult.kind === "ok"
    ? configurationResult.configuration.defaultLocale
    : tenantResult.tenant.defaultLocale;
  const displayTimezone = configurationResult.kind === "ok"
    ? configurationResult.configuration.timezone
    : DEFAULT_OPERATIONS_TIMEZONE;
  const merchantsResult = await getMerchants(session.selectedTenantId, session.accessToken, readOptions);
  if (merchantsResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (merchantsResult.kind !== "ok") return <Unavailable />;

  const merchantResults = await Promise.all(merchantsResult.merchants.map((merchant) => loadMerchantWebhooks(session.selectedTenantId!, merchant, session.accessToken, readOptions)));
  if (merchantResults.some((result) => result.kind === "unauthenticated")) redirect("/api/auth/login?reason=session");
  const visible = merchantResults.filter((result): result is { kind: "ok"; value: MerchantWebhooks } => result.kind === "ok").map((result) => result.value);
  const denied = merchantResults.some((result) => result.kind === "unavailable");

  return (
    <main><section className="shell">
      <a href="/operations">← Operations</a>
      <div className="eyebrow">Developer operations / webhooks</div>
      <h1>Merchant webhooks</h1>
      <p>{tenantResult.tenant.name}. Secrets are encrypted at rest and disclosed only when an endpoint is created or rotated.</p>
      {denied && <div className="panel status error">Some Merchants are outside your effective Merchant scope.</div>}
      {!supportActive && visible.length > 0 && <WebhookCreateForm merchants={visible.map((item) => item.merchant)} csrfToken={session.csrfToken} />}
      {visible.length === 0 ? <div className="panel status">No Merchant webhook endpoints are visible in your authorized scope.</div> : visible.map((item) => <MerchantWebhookSection key={item.merchant.merchantId} value={item} csrfToken={session.csrfToken} supportActive={supportActive} displayLocale={displayLocale} displayTimezone={displayTimezone} />)}
    </section></main>
  );
}

async function loadMerchantWebhooks(tenantId: string, merchant: CoreMerchant, accessToken: string, readOptions: { supportSessionId?: string }) {
  const endpointsResult = await getWebhookEndpoints(tenantId, merchant.merchantId, accessToken, readOptions);
  if (endpointsResult.kind !== "ok") return { kind: endpointsResult.kind } as const;
  const pairs = await Promise.all(endpointsResult.endpoints.map(async (endpoint) => {
    const deliveries = await getWebhookDeliveries(tenantId, merchant.merchantId, endpoint.endpointId, accessToken, readOptions);
    return [endpoint.endpointId, deliveries.kind === "ok" ? deliveries.deliveries : []] as const;
  }));
  return { kind: "ok" as const, value: { merchant, endpoints: endpointsResult.endpoints, deliveries: Object.fromEntries(pairs) } };
}

function MerchantWebhookSection({ value, csrfToken, supportActive, displayLocale, displayTimezone }: { value: MerchantWebhooks; csrfToken: string; supportActive: boolean; displayLocale: string; displayTimezone: string }) {
  return <section className="panel"><div className="eyebrow">Merchant</div><h2>{value.merchant.name}</h2><p className="monospace">{value.merchant.merchantId} · {value.merchant.status}</p>{value.endpoints.length === 0 ? <p>No webhook endpoints.</p> : <div className="data-list">{value.endpoints.map((endpoint) => <div key={endpoint.endpointId}><strong>{endpoint.label} · {endpoint.status}</strong><span>{endpoint.endpointUrl} · key {endpoint.keyVersion}</span><span className="table-secondary">Events: {endpoint.allowedEventTypes.join(", ")}</span>{value.deliveries[endpoint.endpointId]?.length ? <div className="table-wrap"><table><caption className="sr-only">Webhook deliveries for {endpoint.label}</caption><thead><tr><th scope="col">Event</th><th scope="col">Status</th><th scope="col">Attempts</th><th scope="col">Last outcome</th><th scope="col">Updated</th></tr></thead><tbody>{value.deliveries[endpoint.endpointId].map((delivery) => <tr key={delivery.deliveryId}><td>{delivery.eventType}</td><td>{delivery.status}</td><td>{delivery.attemptCount}</td><td>{delivery.lastOutcome ?? "—"}</td><td>{formatOperationsDateTime(delivery.updatedAt, displayLocale, displayTimezone)}</td></tr>)}</tbody></table></div> : <p className="table-secondary">No deliveries recorded.</p>}{!supportActive && <WebhookEndpointActions endpoint={endpoint} merchantId={value.merchant.merchantId} csrfToken={csrfToken} />}</div>)}</div>}</section>;
}

function MissingTenant() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">Choose and verify a Tenant first.</div></section></main>; }
function Unavailable() { return <main><section className="shell"><a href="/operations">← Operations</a><div className="panel status error">This Tenant or its Merchant webhook data is unavailable.</div></section></main>; }
