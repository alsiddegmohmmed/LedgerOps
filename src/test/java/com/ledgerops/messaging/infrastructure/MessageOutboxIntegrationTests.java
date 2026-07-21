package com.ledgerops.messaging.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class MessageOutboxIntegrationTests {

    @Autowired
    private MessageOutbox outbox;

    @Autowired
    private JdbcMessageOutbox jdbcOutbox;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void equivalentBusinessContentReturnsStableOutboxAndMessageIdentity() {
        UUID aggregateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        OutboxMessageDraft firstDraft = draft(aggregateId, tenantId, "{\"value\":\"one\"}");

        StoredOutboxMessage first = outbox.appendOrGet(firstDraft);
        StoredOutboxMessage repeated = outbox.appendOrGet(new OutboxMessageDraft(
                firstDraft.producerName(),
                firstDraft.deduplicationKey(),
                firstDraft.messageType(),
                firstDraft.schemaVersion(),
                firstDraft.aggregateId(),
                firstDraft.tenantId(),
                firstDraft.topic(),
                firstDraft.partitionKey(),
                firstDraft.canonicalPayloadJson(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                firstDraft.occurredAt().plusSeconds(30)
        ));

        assertEquals(first.outboxId(), repeated.outboxId());
        assertEquals(first.messageId(), repeated.messageId());
        assertEquals(first.contentHash(), repeated.contentHash());
    }

    @Test
    void changedBusinessContentUnderOneIdentityIsAConsistencyError() {
        UUID aggregateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        outbox.appendOrGet(draft(aggregateId, tenantId, "{\"value\":\"one\"}"));

        assertThrows(
                OutboxConsistencyException.class,
                () -> outbox.appendOrGet(draft(
                        aggregateId,
                        tenantId,
                        "{\"value\":\"different\"}"
                ))
        );
    }

    @Test
    void contentHashMatchesTheApprovedCanonicalBusinessContentBytes() {
        OutboxMessageDraft draft = draft(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "{\"value\":\"one\"}"
        );

        assertEquals(
                "84ecba77477e880254ce7715eab135785b57d02c40a80a4cc5460a6f7b1650c3",
                jdbcOutbox.contentHash(draft)
        );
    }

    @Test
    void canonicalPayloadBytesRoundTripWithoutJsonbNormalization() {
        String canonical = "{\"z\":\"line\\n\\t☃\",\"amount\":\"12\",\"a\":1}";
        OutboxMessageDraft message = draft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                canonical
        );

        StoredOutboxMessage stored = outbox.appendOrGet(message);

        assertEquals(canonical, stored.canonicalPayloadJson());
    }

    @Test
    void malformedPayloadIsRejectedByJsonParsing() {
        OutboxMessageDraft malformed = draft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{not-json}"
        );

        assertThrows(IllegalArgumentException.class, () -> outbox.appendOrGet(malformed));
    }

    @Test
    void validButNonCanonicalJsonBytesAreRejected() {
        OutboxMessageDraft prettyPrinted = draft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{ \"value\" : \"one\" }"
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> outbox.appendOrGet(prettyPrinted)
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void appendRequiresAnExistingBusinessTransaction() {
        OutboxMessageDraft message = draft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{\"value\":\"one\"}"
        );

        assertThrows(
                IllegalTransactionStateException.class,
                () -> outbox.appendOrGet(message)
        );
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentEquivalentAppendsReturnOneStableRecord() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        OutboxMessageDraft message = draft(
                aggregateId,
                UUID.randomUUID(),
                "{\"value\":\"one\"}"
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<StoredOutboxMessage> first = executor.submit(() -> {
                barrier.await();
                return transactions.execute(status -> outbox.appendOrGet(message));
            });
            Future<StoredOutboxMessage> second = executor.submit(() -> {
                barrier.await();
                return transactions.execute(status -> outbox.appendOrGet(message));
            });

            StoredOutboxMessage firstResult = first.get();
            StoredOutboxMessage secondResult = second.get();
            assertEquals(firstResult.outboxId(), secondResult.outboxId());
            assertEquals(firstResult.messageId(), secondResult.messageId());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM messaging.outbox WHERE producer_name = ? AND deduplication_key = ?",
                    Integer.class,
                    message.producerName().value(),
                    message.deduplicationKey()
            ));
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM messaging.outbox WHERE producer_name = ? AND deduplication_key = ?",
                    message.producerName().value(),
                    message.deduplicationKey()
            );
        }
    }

    private OutboxMessageDraft draft(UUID aggregateId, UUID tenantId, String payload) {
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "payment-submission:" + aggregateId,
                "SubmitPaymentToProvider",
                1,
                aggregateId,
                tenantId,
                "ledgerops.provider.commands.v1",
                aggregateId.toString(),
                payload,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-07-21T12:00:00Z")
        );
    }
}
