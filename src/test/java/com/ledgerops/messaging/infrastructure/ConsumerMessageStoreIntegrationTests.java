package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.api.ConsumerFailureResult;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConsumerMessageStoreIntegrationTests {

    @Autowired
    private ConsumerMessageStore messages;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void inboxAndBusinessEffectCommitOnceAndDuplicateSkipsTheEffect() {
        IncomingMessage message = incoming();
        int[] effects = {0};

        assertEquals(InboxResult.PROCESSED, transaction(message, effects));
        assertEquals(InboxResult.DUPLICATE, transaction(message, effects));

        assertEquals(1, effects[0]);
        assertEquals("PROCESSED", inboxStatus(message));
        cleanup(message);
    }

    @Test
    void failedBusinessEffectRollsBackInboxBeforeFailureIsRecordedSeparately() {
        IncomingMessage message = incoming();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            messages.recordProcessed(message);
            throw new IllegalStateException("business failure");
        }));

        assertEquals(0, inboxCount(message));
        ConsumerFailureResult failure = failure(message, false);
        assertEquals(1, failure.failureCount());
        assertEquals(0, inboxCount(message));
        cleanup(message);
    }

    @Test
    void fifthFailureAtomicallyCreatesDeadInboxAndOneConsumerDeadLetter() {
        IncomingMessage message = incoming();
        ConsumerFailureResult result = null;
        for (int i = 0; i < 5; i++) {
            result = failure(message, false);
        }

        assertTrue(result.dead());
        assertEquals("DEAD", inboxStatus(message));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.consumer_dead_letters
                 WHERE consumer_name = ? AND message_id = ?
                """, Integer.class, message.consumerName(), message.messageId()));
        cleanup(message);
    }

    @Test
    void transportInvalidRecordUsesTopicPartitionOffsetIdentityWithoutInbox() {
        String consumer = "provider-command-consumer-v1";
        messages.recordTransportDeadLetter(
                consumer, "topic", 2, 42, "a".repeat(64), new byte[5000],
                "INVALID_ENVELOPE", "invalid", null
        );
        messages.recordTransportDeadLetter(
                consumer, "topic", 2, 42, "a".repeat(64), new byte[5000],
                "INVALID_ENVELOPE", "invalid", null
        );

        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.transport_dead_letters
                 WHERE consumer_name = ? AND topic = ? AND partition_number = ?
                   AND record_offset = ? AND octet_length(bounded_safe_bytes) = 4096
                """, Integer.class, consumer, "topic", 2, 42));
        jdbc.update("DELETE FROM messaging.transport_dead_letters WHERE consumer_name = ?", consumer);
    }

    private ConsumerFailureResult failure(IncomingMessage message, boolean immediate) {
        return messages.recordFailure(
                message, "{}", "b".repeat(64), "topic", 0, 1,
                "FAILURE", "safe", message.messageId(), immediate
        );
    }

    private InboxResult transaction(IncomingMessage message, int[] effects) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            InboxResult result = messages.recordProcessed(message);
            if (result == InboxResult.PROCESSED) {
                effects[0]++;
            }
            return result;
        });
    }

    private IncomingMessage incoming() {
        return new IncomingMessage(
                "provider-command-consumer-v1", UUID.randomUUID(), UUID.randomUUID(),
                "SubmitPaymentToProvider"
        );
    }

    private int inboxCount(IncomingMessage message) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM messaging.inbox
                 WHERE consumer_name = ? AND message_id = ?
                """, Integer.class, message.consumerName(), message.messageId());
    }

    private String inboxStatus(IncomingMessage message) {
        return jdbc.queryForObject("""
                SELECT status FROM messaging.inbox
                 WHERE consumer_name = ? AND message_id = ?
                """, String.class, message.consumerName(), message.messageId());
    }

    private void cleanup(IncomingMessage message) {
        jdbc.update("DELETE FROM messaging.consumer_dead_letters WHERE consumer_name = ? AND message_id = ?",
                message.consumerName(), message.messageId());
        jdbc.update("DELETE FROM messaging.consumer_failures WHERE consumer_name = ? AND message_id = ?",
                message.consumerName(), message.messageId());
        jdbc.update("DELETE FROM messaging.inbox WHERE consumer_name = ? AND message_id = ?",
                message.consumerName(), message.messageId());
    }
}
