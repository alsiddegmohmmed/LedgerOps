package com.ledgerops.audit.application;

import com.ledgerops.audit.api.AuditSearchItem;
import com.ledgerops.audit.api.AuditSearchPage;
import com.ledgerops.audit.api.AuditSearchPort;
import com.ledgerops.audit.api.AuditSearchQuery;
import com.ledgerops.audit.api.InvalidAuditCursorException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
class AuditSearchService implements AuditSearchPort {

    private static final int BATCH_SIZE = 101;
    private final AuditSearchStore store;

    AuditSearchService(AuditSearchStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public AuditSearchPage findPage(AuditSearchQuery query) {
        String fingerprint = AuditSearchFingerprint.of(query);
        AuditPageCursor cursor = query.cursor() == null
                ? null : AuditPageCursorCodec.decode(query.cursor());
        if (cursor != null && (cursor.version() != 1
                || !fingerprint.equals(cursor.queryFingerprint()))) {
            throw new InvalidAuditCursorException();
        }
        AuditSearchStore.Batch batch = store.findBatch(
                query,
                cursor == null ? null : cursor.occurredAt(),
                cursor == null ? null : cursor.auditId(),
                query.limit());
        List<AuditSearchItem> items = batch.rows().stream()
                .map(row -> new AuditSearchItem(
                        row.auditId(), row.actorIssuer(), row.actorSubject(), row.principalType(),
                        row.tenantId(), row.action(), row.entity(), row.entityId(),
                        row.correlationId(), row.reason(), row.details(), row.occurredAt()))
                .toList();
        String nextCursor = batch.hasMore() && !items.isEmpty()
                ? AuditPageCursorCodec.encode(new AuditPageCursor(
                1, items.getLast().occurredAt(), items.getLast().auditId(), fingerprint))
                : null;
        return new AuditSearchPage(items, nextCursor);
    }
}
