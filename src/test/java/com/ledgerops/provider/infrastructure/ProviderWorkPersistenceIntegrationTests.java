package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.provider.application.ProviderSubmissionCommand;
import com.ledgerops.provider.application.ProviderWorkConsistencyException;
import com.ledgerops.provider.application.AcceptProviderSubmissionCommand;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProviderWorkPersistenceIntegrationTests {

    @Autowired
    private AcceptProviderSubmissionCommand acceptance;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void equivalentCommandsWithDifferentMessageIdsCreateOneSubmissionWorkItem() {
        ProviderSubmissionCommand first = command();
        IncomingMessage firstMessage = incoming(first, first.messageId());
        UUID repeatedMessageId = UUID.randomUUID();
        ProviderSubmissionCommand repeated = new ProviderSubmissionCommand(
                first.tenantId(), repeatedMessageId, first.attemptId(), first.paymentId(),
                first.attemptSequence(), first.providerId(), first.providerIdempotencyKey(), first.requestIntentHash(),
                first.canonicalPayload(), first.correlationId(), first.causationId(),
                first.traceparent(), first.tracestate()
        );
        IncomingMessage repeatedMessage = incoming(repeated, repeatedMessageId);

        acceptance.accept(firstMessage, first);
        acceptance.accept(repeatedMessage, repeated);

        assertEquals(1, workCount(first));
        assertEquals(first.messageId(), jdbc.queryForObject("""
                SELECT causation_id FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ?
                """, UUID.class, first.tenantId(), first.attemptId()));
        deactivate(first);
    }

    @Test
    void changedCommandContentUnderOneWorkIdentityIsRejectedWithoutReplacement() {
        ProviderSubmissionCommand first = command();
        acceptance.accept(incoming(first, first.messageId()), first);
        ProviderSubmissionCommand changed = new ProviderSubmissionCommand(
                first.tenantId(), UUID.randomUUID(), first.attemptId(), first.paymentId(),
                first.attemptSequence(), first.providerId(), first.providerIdempotencyKey(), "b".repeat(64),
                first.canonicalPayload(), first.correlationId(), first.causationId(),
                first.traceparent(), first.tracestate()
        );

        assertThrows(ProviderWorkConsistencyException.class, () -> acceptance.accept(
                incoming(changed, changed.messageId()), changed
        ));

        assertEquals("a".repeat(64), jdbc.queryForObject("""
                SELECT request_intent_hash FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ?
                """, String.class, first.tenantId(), first.attemptId()));
        deactivate(first);
    }

    @Test
    void inboxAndProviderWorkRollBackTogetherWhenConsumerBusinessTransactionFails() {
        ProviderSubmissionCommand command = command();
        IncomingMessage incoming = incoming(command, command.messageId());

        assertThrows(RuntimeException.class, () -> acceptance.accept(
                incoming,
                new ProviderSubmissionCommand(
                        command.tenantId(), command.messageId(), command.attemptId(),
                        command.paymentId(), command.attemptSequence(), command.providerId(),
                        command.providerIdempotencyKey(), command.requestIntentHash(),
                        "not-json", command.correlationId(), command.causationId(),
                        command.traceparent(), command.tracestate()
                )
        ));

        assertEquals(0, workCount(command));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM messaging.inbox
                 WHERE consumer_name = ? AND message_id = ?
                """, Integer.class, incoming.consumerName(), incoming.messageId()));
    }

    private ProviderSubmissionCommand command() {
        UUID paymentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        return new ProviderSubmissionCommand(
                UUID.randomUUID(), UUID.randomUUID(), attemptId, paymentId,
                1, "SIMULATOR", "payment:" + paymentId, "a".repeat(64),
                "{\"attemptId\":\"" + attemptId + "\"}",
                UUID.randomUUID(), UUID.randomUUID(),
                "00-00000000000000000000000000000001-0000000000000002-01", null
        );
    }

    private IncomingMessage incoming(ProviderSubmissionCommand command, UUID messageId) {
        return new IncomingMessage(
                "provider-command-consumer-v1", messageId, command.tenantId(),
                "SubmitPaymentToProvider"
        );
    }

    private int workCount(ProviderSubmissionCommand command) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ?
                """, Integer.class, command.tenantId(), command.attemptId());
    }

    private void deactivate(ProviderSubmissionCommand command) {
        jdbc.update("""
                UPDATE provider.work SET status = 'UNRESOLVED'
                 WHERE tenant_id = ? AND attempt_id = ?
                """, command.tenantId(), command.attemptId());
    }

}
