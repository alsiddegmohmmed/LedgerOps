package com.ledgerops.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.ledger.domain.LedgerAccountId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class LedgerStatementQueryTests {

    private static final Instant START = Instant.parse("2026-07-20T00:00:00Z");
    private static final Instant END = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void acceptsExplicitHalfOpenBoundsAndMaximumPageSize() {
        LedgerStatementQuery query = new LedgerStatementQuery(
                UUID.randomUUID(),
                LedgerAccountId.newId(),
                START,
                END,
                0,
                LedgerStatementQuery.MAXIMUM_LIMIT
        );

        assertEquals(START, query.fromInclusive());
        assertEquals(END, query.toExclusive());
        assertEquals(100, query.limit());
    }

    @Test
    void rejectsEmptyOrReversedTimeRanges() {
        assertThrows(
                IllegalArgumentException.class,
                () -> query(START, START, 0, 10)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> query(END, START, 0, 10)
        );
    }

    @Test
    void rejectsUnboundedOrInvalidPages() {
        assertThrows(
                IllegalArgumentException.class,
                () -> query(START, END, -1, 10)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> query(START, END, 0, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> query(
                        START,
                        END,
                        0,
                        LedgerStatementQuery.MAXIMUM_LIMIT + 1
                )
        );
    }

    private LedgerStatementQuery query(
            Instant fromInclusive,
            Instant toExclusive,
            int offset,
            int limit
    ) {
        return new LedgerStatementQuery(
                UUID.randomUUID(),
                LedgerAccountId.newId(),
                fromInclusive,
                toExclusive,
                offset,
                limit
        );
    }
}
