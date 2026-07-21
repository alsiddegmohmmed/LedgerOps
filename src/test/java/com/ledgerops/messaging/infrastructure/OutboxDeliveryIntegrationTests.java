package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.messaging.application.OutboxClaim;
import com.ledgerops.messaging.application.OutboxDeliveryStore;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxDeliveryIntegrationTests {

    @Autowired
    private MessageOutbox outbox;

    @Autowired
    private OutboxDeliveryStore delivery;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void cleanMessagingDeliveryEvidence() {
        jdbc.update("DELETE FROM messaging.publication_dead_letters");
        jdbc.update("DELETE FROM messaging.outbox");
    }

    @Test
    void dueRowIsClaimedWithAThirtySecondFencedLeaseAndPublishedByCurrentHolder() {
        StoredOutboxMessage message = append();
        Instant now = Instant.now().plusSeconds(1);

        OutboxClaim claim = only(delivery.claimDue("worker-a", now, Duration.ofSeconds(30), 10));

        assertEquals(message.outboxId(), claim.outboxId());
        assertEquals(1, claim.attemptCount());
        assertTrue(delivery.renew(claim.outboxId(), claim.leaseToken(), now.plusSeconds(40)));
        assertFalse(delivery.markPublished(claim.outboxId(), UUID.randomUUID(), now));
        assertTrue(delivery.markPublished(claim.outboxId(), claim.leaseToken(), now));
        assertEquals("PUBLISHED", status(message.outboxId()));
    }

    @Test
    void expiredClaimIsRecoveredWithANewTokenAndStaleHolderCannotMutateIt() {
        StoredOutboxMessage message = append();
        Instant now = Instant.now().plusSeconds(1);
        OutboxClaim first = only(delivery.claimDue("worker-a", now, Duration.ofSeconds(30), 10));
        jdbc.update("UPDATE messaging.outbox SET lease_expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minusSeconds(1)), message.outboxId());

        OutboxClaim reclaimed = only(delivery.claimDue(
                "worker-b", now, Duration.ofSeconds(30), 10
        ));

        assertNotEquals(first.leaseToken(), reclaimed.leaseToken());
        assertFalse(delivery.markRetryable(
                message.outboxId(), first.leaseToken(), now, "STALE", "stale"
        ));
        assertTrue(delivery.markRetryable(
                message.outboxId(), reclaimed.leaseToken(), now.plusSeconds(1),
                "KAFKA_DOWN", "recoverable"
        ));
        assertEquals("RETRYABLE", status(message.outboxId()));
    }

    @Test
    void tenthFailedPublicationCreatesOnePublicationDeadLetter() {
        StoredOutboxMessage message = append();
        jdbc.update("UPDATE messaging.outbox SET attempt_count = 9 WHERE id = ?", message.outboxId());
        Instant now = Instant.now().plusSeconds(1);
        OutboxClaim claim = only(delivery.claimDue("worker", now, Duration.ofSeconds(30), 10));

        assertEquals(10, claim.attemptCount());
        assertTrue(delivery.markDead(
                message.outboxId(), claim.leaseToken(), now, "EXHAUSTED", "dead"
        ));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM messaging.publication_dead_letters WHERE outbox_id = ?",
                Integer.class, message.outboxId()
        ));
    }

    @Test
    void expiredFinalAttemptIsDeadLetteredWithoutAnEleventhPublication() {
        StoredOutboxMessage message = append();
        Instant now = Instant.now().plusSeconds(1);
        jdbc.update("""
                UPDATE messaging.outbox
                   SET status = 'CLAIMED', attempt_count = 10,
                       lease_owner = 'crashed', lease_token = ?, lease_expires_at = ?
                 WHERE id = ?
                """, UUID.randomUUID(), java.sql.Timestamp.from(now.minusSeconds(1)),
                message.outboxId());

        assertTrue(delivery.claimDue("replacement", now, Duration.ofSeconds(30), 10).isEmpty());
        assertEquals("DEAD", status(message.outboxId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.publication_dead_letters
                 WHERE outbox_id = ? AND reason_code = 'FINAL_ATTEMPT_LEASE_EXPIRED'
                """, Integer.class, message.outboxId()));
    }

    private StoredOutboxMessage append() {
        UUID paymentId = UUID.randomUUID();
        OutboxMessageDraft draft = new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "payment-submission:" + UUID.randomUUID(),
                "SubmitPaymentToProvider",
                1,
                paymentId,
                UUID.randomUUID(),
                "ledgerops.provider.commands.v1",
                paymentId.toString(),
                "{\"value\":\"one\"}",
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now()
        );
        return new TransactionTemplate(transactionManager)
                .execute(status -> outbox.appendOrGet(draft));
    }

    private OutboxClaim only(List<OutboxClaim> claims) {
        assertEquals(1, claims.size());
        return claims.getFirst();
    }

    private String status(UUID id) {
        return jdbc.queryForObject(
                "SELECT status FROM messaging.outbox WHERE id = ?", String.class, id
        );
    }
}
