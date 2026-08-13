"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

type LiveState = "LIVE" | "RECONNECTING" | "STALE";

const STALE_AFTER_MS = 30_000;

export function LiveSummaryStatus({
  tenantId,
  cursor,
  merchantIds,
}: {
  tenantId: string;
  cursor: number;
  merchantIds: string[];
}) {
  const router = useRouter();
  const [state, setState] = useState<LiveState>("RECONNECTING");

  useEffect(() => {
    const params = new URLSearchParams({
      tenantId,
      cursor: String(cursor),
    });
    for (const merchantId of merchantIds) params.append("merchantId", merchantId);

    const source = new EventSource(`/api/reports/events?${params.toString()}`);
    let staleTimer: ReturnType<typeof setTimeout> | undefined;
    let disposed = false;

    const clearStaleTimer = () => {
      if (staleTimer) clearTimeout(staleTimer);
      staleTimer = undefined;
    };
    const markReconnecting = () => {
      if (disposed) return;
      setState("RECONNECTING");
      clearStaleTimer();
      staleTimer = setTimeout(() => {
        if (!disposed) setState("STALE");
      }, STALE_AFTER_MS);
    };
    const refreshSnapshot = () => {
      if (disposed) return;
      markReconnecting();
      source.close();
      router.refresh();
    };

    source.onopen = () => {
      clearStaleTimer();
      if (!disposed) setState("LIVE");
    };
    source.onerror = markReconnecting;
    source.addEventListener("projection-updated", (event) => {
      try {
        const payload = JSON.parse((event as MessageEvent).data) as { affected?: unknown };
        if (Array.isArray(payload.affected) && payload.affected.includes("OPERATIONAL_SUMMARY")) {
          refreshSnapshot();
        }
      } catch {
        // A malformed invalidation cannot be used to refresh derived state.
        markReconnecting();
      }
    });
    source.addEventListener("resync-required", () => {
      if (disposed) return;
      clearStaleTimer();
      setState("STALE");
      source.close();
      router.refresh();
    });

    return () => {
      disposed = true;
      clearStaleTimer();
      source.close();
    };
  }, [cursor, merchantIds, router, tenantId]);

  return <span className={`status-badge live-${state.toLowerCase()}`}>Live updates: {state}</span>;
}
