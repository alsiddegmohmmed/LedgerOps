export function TenantSelector({
  csrfToken,
  selectedTenantId,
}: {
  csrfToken: string;
  selectedTenantId?: string;
}) {
  return (
    <form className="panel" action="/api/tenant/select" method="post">
      <h2>Choose a Tenant</h2>
      <p>The selected ID is untrusted and is verified by Core before it is remembered.</p>
      <label htmlFor="tenantId">Tenant ID</label>
      <input id="tenantId" name="tenantId" defaultValue={selectedTenantId} required pattern="[0-9a-fA-F-]{36}" />
      <input type="hidden" name="csrfToken" value={csrfToken} />
      <button type="submit">Verify Tenant</button>
    </form>
  );
}
