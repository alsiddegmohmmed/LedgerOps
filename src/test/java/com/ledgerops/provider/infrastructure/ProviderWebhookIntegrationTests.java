package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.provider.application.ProviderWebhookClaim;
import com.ledgerops.provider.application.ProviderWebhookExecutionStore;
import com.ledgerops.provider.application.ProviderWebhookProcessingOutcome;
import com.ledgerops.provider.application.ProviderWorkConsistencyException;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "ledgerops.provider.webhook.enabled=true",
        "ledgerops.provider.webhook.processing.enabled=false",
        "ledgerops.provider.webhook.key-id=simulator-to-core-v1",
        "ledgerops.provider.webhook.secret=test-only-simulator-to-core-secret",
        "ledgerops.provider.webhook.provider-client-id=ledgerops-core"
})
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ProviderWebhookIntegrationTests {
    private static final String PATH = "/internal/provider/v1/webhooks";
    private static final String KEY_ID = "simulator-to-core-v1";
    private static final String SECRET = "test-only-simulator-to-core-secret";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderWebhookExecutionStore executionStore;
    @Autowired MessageOutbox outbox;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void authenticationFailuresPersistOnlyBoundedPlatformEvidence() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        byte[] body = payload(eventId, UUID.randomUUID(), paymentId, "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);
        long before = count("provider.platform_security_rejections");

        request(body, "unknown-key", now(), eventId.toString(), "v1=invalid")
                .andExpect(status().isUnauthorized());
        request(body, KEY_ID, now(), eventId.toString(), "v1=invalid")
                .andExpect(status().isUnauthorized());
        String oldTimestamp = Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        request(body, KEY_ID, oldTimestamp, eventId.toString(),
                signer().signForTest(oldTimestamp, eventId.toString(), body))
                .andExpect(status().isUnauthorized());
        request(body, KEY_ID, now(), "not-a-uuid", "v1=invalid")
                .andExpect(status().isUnauthorized());

        assertEquals(before + 4, count("provider.platform_security_rejections"));
        assertEquals(0, countByEvent("provider.webhook_receipts", eventId));
        assertEquals(0, countByEvent("provider.webhook_events", eventId));
        assertEquals(0, countByEvent("provider.unattributed_webhook_evidence", eventId));
    }

    @Test
    void oversizedBodyPersistsOnlyPlatformRejectionMetadata() throws Exception {
        byte[] body = ("{\"value\":\"" + "x".repeat(256 * 1024) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        UUID eventId = UUID.randomUUID();
        long before = count("provider.platform_security_rejections");

        request(body, KEY_ID, now(), eventId.toString(), "v1=not-checked")
                .andExpect(status().isPayloadTooLarge());

        assertEquals(before + 1, count("provider.platform_security_rejections"));
        assertEquals(0, countByEvent("provider.webhook_receipts", eventId));
        assertEquals(0, countByEvent("provider.webhook_events", eventId));
    }

    @Test
    void authenticatedMalformedAndUnmappedPayloadsRemainUnattributed() throws Exception {
        UUID malformedEventId = UUID.randomUUID();
        byte[] malformed = "{".getBytes(StandardCharsets.UTF_8);
        performSigned(malformed, malformedEventId).andExpect(status().isBadRequest());
        assertEquals(1, countByEvent(
                "provider.unattributed_webhook_evidence", malformedEventId));
        assertEquals(0, countByEvent("provider.webhook_receipts", malformedEventId));

        UUID unmappedEventId = UUID.randomUUID();
        byte[] unmapped = payload(unmappedEventId, UUID.randomUUID(), UUID.randomUUID(),
                "PENDING").getBytes(StandardCharsets.UTF_8);
        performSigned(unmapped, unmappedEventId).andExpect(status().isAccepted());
        assertEquals(1, countByEvent(
                "provider.unattributed_webhook_evidence", unmappedEventId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_operational_events
                 WHERE provider_event_id = ? AND event_type = 'UNMAPPED'
                """, Integer.class, unmappedEventId));
        assertEquals(0, countByEvent("provider.webhook_receipts", unmappedEventId));
        assertEquals(0, countByEvent("provider.webhook_events", unmappedEventId));
    }

    @Test
    void mappedReceptionIsDurableDuplicateSafeAndPayloadConflictPreserving()
            throws Exception {
        Mapping mapping = mapping();
        UUID eventId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        byte[] body = payload(eventId, resultId, mapping.paymentId(), "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);

        performSigned(body, eventId).andExpect(status().isAccepted());
        performSigned(body, eventId).andExpect(status().isAccepted());
        byte[] changed = payload(eventId, resultId, mapping.paymentId(), "DECLINED")
                .getBytes(StandardCharsets.UTF_8);
        performSigned(changed, eventId).andExpect(status().isConflict());

        assertEquals(1, countByEvent("provider.webhook_events", eventId));
        assertEquals(3, countByEvent("provider.webhook_receipts", eventId));
        assertEquals(1, receiptOutcome(eventId, "NEW"));
        assertEquals(1, receiptOutcome(eventId, "DUPLICATE"));
        assertEquals(1, receiptOutcome(eventId, "CONFLICT"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_operational_events
                 WHERE provider_event_id = ? AND event_type = 'PAYLOAD_CONFLICT'
                """, Integer.class, eventId));

        ProviderWebhookClaim claim = claim(eventId);
        assertEquals(ProviderWebhookProcessingOutcome.COMPLETED,
                executionStore.processWebhook(claim));
    }

    @Test
    void providerMappingEnforcesTenantIsolationAndRejectsCrossTenantAmbiguity()
            throws Exception {
        Mapping first = mapping();
        Mapping second = mapping();
        UUID mappedEventId = UUID.randomUUID();
        UUID mappedResultId = UUID.randomUUID();

        performSigned(payload(mappedEventId, mappedResultId, first.paymentId(), "SUCCESS")
                .getBytes(StandardCharsets.UTF_8), mappedEventId)
                .andExpect(status().isAccepted());

        assertEquals(first.tenantId(), jdbc.queryForObject("""
                SELECT tenant_id FROM provider.webhook_events
                 WHERE provider_event_id = ?
                """, UUID.class, mappedEventId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_events
                 WHERE provider_event_id = ? AND tenant_id = ?
                """, Integer.class, mappedEventId, second.tenantId()));
        executionStore.processWebhook(claim(mappedEventId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.results
                 WHERE provider_result_id = ? AND tenant_id = ?
                """, Integer.class, mappedResultId, first.tenantId()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM provider.results
                 WHERE provider_result_id = ? AND tenant_id = ?
                """, Integer.class, mappedResultId, second.tenantId()));

        UUID sharedPaymentId = UUID.randomUUID();
        mapping(UUID.randomUUID(), sharedPaymentId, UUID.randomUUID());
        mapping(UUID.randomUUID(), sharedPaymentId, UUID.randomUUID());
        UUID ambiguousEventId = UUID.randomUUID();
        performSigned(payload(ambiguousEventId, UUID.randomUUID(), sharedPaymentId, "PENDING")
                .getBytes(StandardCharsets.UTF_8), ambiguousEventId)
                .andExpect(status().isAccepted());

        assertEquals(1, countByEvent(
                "provider.unattributed_webhook_evidence", ambiguousEventId));
        assertEquals(0, countByEvent("provider.webhook_receipts", ambiguousEventId));
        assertEquals(0, countByEvent("provider.webhook_events", ambiguousEventId));
    }

    @Test
    void asynchronousProcessingUsesFencedLeaseAndCommitsStageAOnce() throws Exception {
        Mapping mapping = mapping();
        UUID eventId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        byte[] body = payload(eventId, resultId, mapping.paymentId(), "SUCCESS")
                .getBytes(StandardCharsets.UTF_8);
        performSigned(body, eventId).andExpect(status().isAccepted());

        ProviderWebhookClaim abandoned = claim(eventId);
        jdbc.update("""
                UPDATE provider.webhook_events
                   SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE event_id = ?
                """, abandoned.eventId());
        ProviderWebhookClaim reclaimed = claim(eventId);
        assertNotEquals(abandoned.leaseToken(), reclaimed.leaseToken());
        assertThrows(ProviderWorkConsistencyException.class,
                () -> executionStore.processWebhook(abandoned));
        assertEquals(ProviderWebhookProcessingOutcome.COMPLETED,
                executionStore.processWebhook(reclaimed));

        assertEquals("COMPLETED", jdbc.queryForObject("""
                SELECT status FROM provider.webhook_events WHERE event_id = ?
                """, String.class, reclaimed.eventId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.interactions
                 WHERE webhook_event_id = ? AND work_type = 'WEBHOOK'
                """, Integer.class, reclaimed.eventId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.results
                 WHERE tenant_id = ? AND provider_id = 'SIMULATOR'
                   AND provider_result_id = ? AND evidence_origin = 'WEBHOOK'
                """, Integer.class, mapping.tenantId(), resultId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox
                 WHERE producer_name = 'provider' AND deduplication_key = ?
                   AND message_type = 'ProviderResultObserved'
                """, Integer.class,
                "provider-result:" + mapping.tenantId() + ":SIMULATOR:" + resultId));
    }

    @Test
    void duplicateObservationKeepsReceiptsAndInteractionsButOneResultAndOutbox()
            throws Exception {
        Mapping mapping = mapping();
        UUID resultId = UUID.randomUUID();
        UUID firstEvent = UUID.randomUUID();
        UUID secondEvent = UUID.randomUUID();
        performSigned(payload(firstEvent, resultId, mapping.paymentId(), "SUCCESS")
                .getBytes(StandardCharsets.UTF_8), firstEvent).andExpect(status().isAccepted());
        executionStore.processWebhook(claim(firstEvent));
        performSigned(payload(secondEvent, resultId, mapping.paymentId(), "SUCCESS")
                .getBytes(StandardCharsets.UTF_8), secondEvent).andExpect(status().isAccepted());
        executionStore.processWebhook(claim(secondEvent));

        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM provider.interactions
                 WHERE tenant_id = ? AND work_type = 'WEBHOOK'
                   AND request_id IN (?, ?)
                """, Integer.class, mapping.tenantId(), firstEvent, secondEvent));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.results
                 WHERE tenant_id = ? AND provider_result_id = ?
                """, Integer.class, mapping.tenantId(), resultId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox WHERE deduplication_key = ?
                """, Integer.class,
                "provider-result:" + mapping.tenantId() + ":SIMULATOR:" + resultId));
    }

    @Test
    void conflictingResultIdentityBecomesDurableUnresolvedEvidence() throws Exception {
        Mapping mapping = mapping();
        UUID resultId = UUID.randomUUID();
        UUID successEvent = UUID.randomUUID();
        performSigned(payload(successEvent, resultId, mapping.paymentId(), "SUCCESS")
                .getBytes(StandardCharsets.UTF_8), successEvent).andExpect(status().isAccepted());
        executionStore.processWebhook(claim(successEvent));

        UUID conflictEvent = UUID.randomUUID();
        performSigned(payload(conflictEvent, resultId, mapping.paymentId(), "DECLINED")
                .getBytes(StandardCharsets.UTF_8), conflictEvent).andExpect(status().isAccepted());
        assertEquals(ProviderWebhookProcessingOutcome.RESULT_CONFLICT,
                executionStore.processWebhook(claim(conflictEvent)));

        assertEquals("UNRESOLVED", jdbc.queryForObject("""
                SELECT status FROM provider.webhook_events
                 WHERE provider_event_id = ?
                """, String.class, conflictEvent));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_operational_events
                 WHERE provider_event_id = ? AND event_type = 'RESULT_CONFLICT'
                """, Integer.class, conflictEvent));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.results
                 WHERE tenant_id = ? AND provider_result_id = ?
                """, Integer.class, mapping.tenantId(), resultId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.outbox WHERE deduplication_key = ?
                """, Integer.class,
                "provider-result:" + mapping.tenantId() + ":SIMULATOR:" + resultId));
    }

    @Test
    void nonFinalWebhookSchedulesStatusRecoveryWithoutRepeatingSubmissionWork()
            throws Exception {
        Mapping mapping = mapping();
        UUID eventId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        performSigned(payload(eventId, resultId, mapping.paymentId(), "PENDING")
                .getBytes(StandardCharsets.UTF_8), eventId).andExpect(status().isAccepted());

        executionStore.processWebhook(claim(eventId));

        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'STATUS_QUERY'
                   AND status = 'PENDING'
                """, Integer.class, mapping.tenantId(), mapping.attemptId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                   AND execution_count = 0
                """, Integer.class, mapping.tenantId(), mapping.attemptId()));
    }

    @Test
    void stageAOutboxFailureRollsBackInteractionResultAndWebhookCompletion()
            throws Exception {
        Mapping mapping = mapping();
        UUID eventId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();
        performSigned(payload(eventId, resultId, mapping.paymentId(), "SUCCESS")
                .getBytes(StandardCharsets.UTF_8), eventId).andExpect(status().isAccepted());
        ProviderWebhookClaim claim = claim(eventId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outbox.appendOrGet(new OutboxMessageDraft(
                        ProducerName.PROVIDER,
                        "provider-result:" + mapping.tenantId()
                                + ":SIMULATOR:" + resultId,
                        "ProviderResultObserved", 1, mapping.paymentId(), mapping.tenantId(),
                        "ledgerops.provider.results.v1", mapping.paymentId().toString(),
                        "{\"conflicting\":true}", UUID.randomUUID(), UUID.randomUUID(),
                        Instant.now())));

        assertThrows(com.ledgerops.messaging.api.OutboxConsistencyException.class,
                () -> executionStore.processWebhook(claim));

        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM provider.interactions WHERE webhook_event_id = ?
                """, Integer.class, claim.eventId()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM provider.results
                 WHERE tenant_id = ? AND provider_result_id = ?
                """, Integer.class, mapping.tenantId(), resultId));
        assertEquals("CLAIMED", jdbc.queryForObject("""
                SELECT status FROM provider.webhook_events WHERE event_id = ?
                """, String.class, claim.eventId()));
    }

    private Mapping mapping() {
        return mapping(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private Mapping mapping(UUID tenantId, UUID paymentId, UUID attemptId) {
        Mapping mapping = new Mapping(tenantId, paymentId, attemptId);
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, attempt_sequence, work_type,
                     status, provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, due_at, correlation_id, causation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 'SUBMISSION', 'COMPLETED', 'SIMULATOR', ?, ?, '{}',
                        ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), mapping.tenantId(), mapping.attemptId(),
                mapping.paymentId(), "payment:" + mapping.paymentId(), "a".repeat(64),
                Timestamp.from(now), UUID.randomUUID(), UUID.randomUUID(),
                Timestamp.from(now), Timestamp.from(now));
        return mapping;
    }

    private ProviderWebhookClaim claim(UUID providerEventId) {
        for (int count = 0; count < 20; count++) {
            ProviderWebhookClaim claim = executionStore.claimNextWebhook("test-worker")
                    .orElseThrow();
            if (claim.providerEventId().equals(providerEventId)) {
                return claim;
            }
            executionStore.processWebhook(claim);
        }
        throw new AssertionError("Expected webhook was not claimable");
    }

    private org.springframework.test.web.servlet.ResultActions performSigned(
            byte[] body, UUID eventId) throws Exception {
        String timestamp = now();
        return request(body, KEY_ID, timestamp, eventId.toString(),
                signer().signForTest(timestamp, eventId.toString(), body));
    }

    private org.springframework.test.web.servlet.ResultActions request(
            byte[] body, String keyId, String timestamp, String eventId, String signature)
            throws Exception {
        return mvc.perform(post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-LedgerOps-Key-Id", keyId)
                .header("X-LedgerOps-Timestamp", timestamp)
                .header("X-LedgerOps-Event-Id", eventId)
                .header("X-LedgerOps-Signature", signature));
    }

    private WebhookHmacAuthenticator signer() {
        return new WebhookHmacAuthenticator(KEY_ID, SECRET, "ledgerops-core",
                Clock.systemUTC());
    }

    private String payload(UUID eventId, UUID resultId, UUID paymentId, String category) {
        return "{" +
                "\"providerEventId\":\"" + eventId + "\"," +
                "\"providerResultId\":\"" + resultId + "\"," +
                "\"providerIdempotencyKey\":\"payment:" + paymentId + "\"," +
                "\"providerReference\":\"simulator-transaction-" + paymentId + "\"," +
                "\"providerResultCategory\":\"" + category + "\"," +
                "\"providerOccurredAt\":\"2026-07-21T12:00:00Z\"}";
    }

    private String now() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private long count(String table) {
        if (!table.startsWith("provider.")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private int countByEvent(String table, UUID eventId) {
        if (!table.startsWith("provider.")) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT count(*) FROM " + table
                + " WHERE provider_event_id = ?", Integer.class, eventId);
    }

    private int receiptOutcome(UUID eventId, String outcome) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_receipts
                 WHERE provider_event_id = ? AND outcome = ?
                """, Integer.class, eventId, outcome);
    }

    private record Mapping(UUID tenantId, UUID paymentId, UUID attemptId) {
    }
}
