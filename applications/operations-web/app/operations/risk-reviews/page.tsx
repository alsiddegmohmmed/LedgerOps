import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getRiskReviewQueue, getTenant, type CoreRiskReview } from "../../../lib/core";
import { redis } from "../../../lib/redis";
import { isSessionExpired, isSupportSessionActive, readSession, SESSION_COOKIE } from "../../../lib/session";
import { RiskReviewActions } from "./risk-review-actions";

export const dynamic = "force-dynamic";

export default async function RiskReviewsPage() {
  const cookieStore = await cookies();
  const sessionId = cookieStore.get(SESSION_COOKIE)?.value;
  const session = await readSession(redis(), sessionId);
  if (!session || isSessionExpired(session)) redirect("/api/auth/login");

  const supportActive = isSupportSessionActive(session);
  const tenantId = supportActive ? session.supportTenantId : session.selectedTenantId;
  if (!tenantId) return <Message title="Risk reviews" text="Choose and verify a Tenant first." />;

  const readOptions = supportActive ? { supportSessionId: session.supportSessionId } : {};
  const tenantResult = await getTenant(tenantId, session.accessToken, readOptions);
  if (tenantResult.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (tenantResult.kind !== "ok") return <Message title="Risk reviews" text="This Tenant is unavailable right now." />;

  const result = await getRiskReviewQueue(tenantId, session.accessToken, readOptions);
  if (result.kind === "unauthenticated") redirect("/api/auth/login?reason=session");
  if (result.kind !== "ok") return <Message title="Risk reviews" text="Risk reviews could not be loaded right now." />;

  return (
    <main>
      <section className="shell">
        <a href="/operations">← Operations</a>
        <div className="eyebrow">Risk operations</div>
        <h1>Manual risk reviews</h1>
        <p>{tenantResult.tenant.name}. Review age, priority, score, ownership, and decision state.</p>
        {supportActive && <div className="panel status">Support mode is active. This queue is read-only.</div>}
        <RiskReviewTable
          reviews={result.reviews}
          csrfToken={session.csrfToken}
          readOnly={supportActive}
        />
      </section>
    </main>
  );
}

function RiskReviewTable({
  reviews,
  csrfToken,
  readOnly,
}: {
  reviews: CoreRiskReview[];
  csrfToken: string;
  readOnly: boolean;
}) {
  return (
    <div className="panel">
      {reviews.length === 0 ? <p>No manual risk reviews are currently visible.</p> : (
        <div className="table-wrap"><table>
          <caption className="sr-only">Manual risk review queue</caption>
          <thead><tr>
            <th scope="col">Review</th><th scope="col">Status</th><th scope="col">Priority</th>
            <th scope="col">Due</th><th scope="col">Payment</th><th scope="col">Decision</th><th scope="col">Actions</th>
          </tr></thead>
          <tbody>{reviews.map((review) => (
            <tr key={review.reviewId}>
              <th scope="row" className="monospace">{review.reviewId}<span className="table-secondary">Merchant: {review.merchantId ?? "Tenant-wide"}</span></th>
              <td><span className="status-badge">{review.status}</span><span className="table-secondary">Analyst: {review.assignedAnalystId ?? "Unassigned"}</span></td>
              <td>{review.priority}</td>
              <td>{formatDate(review.dueAt)}<span className="table-secondary">{overdueText(review.dueAt)}</span></td>
              <td className="monospace">{review.paymentId}</td>
              <td>{review.decision ?? "—"}<span className="table-secondary">{review.caseId ? `Case ${review.caseId}` : ""}</span></td>
              <td><RiskReviewActions review={review} csrfToken={csrfToken} readOnly={readOnly} /></td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
    </div>
  );
}

function formatDate(value: string) { return new Date(value).toLocaleString(); }
function overdueText(value: string) { return new Date(value).getTime() <= Date.now() ? "Overdue" : "Within target"; }

function Message({ title, text }: { title: string; text: string }) {
  return <main><section className="shell"><a href="/operations">← Operations</a><h1>{title}</h1><div className="panel status error">{text}</div></section></main>;
}
