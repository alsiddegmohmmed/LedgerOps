package com.ledgerops.reconciliation.application;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.reconciliation.domain.ReconciliationDiscrepancyCategory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes the Reconciliation-owned Case command without depending on Casework.
 *
 * Slice 8 does not define a separate discrepancy SLA. The required Case dueAt
 * therefore equals the source discrepancy creation time; this carries no new
 * future deadline policy. Slice 4's 24-hour rule remains RiskReview-only.
 */
@Service
public class ReconciliationCaseCommandService {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MessageOutbox outbox;

    public ReconciliationCaseCommandService(MessageOutbox outbox) {
        this.outbox = Objects.requireNonNull(outbox, "Message outbox must not be null");
    }

    public void publishDiscrepancyCommands(
            UUID tenantId,
            UUID runId,
            List<ReconciliationResultDraft> results,
            Instant occurredAt
    ) {
        for (ReconciliationResultDraft result : results) {
            if (result.discrepancyCategory() == null) {
                continue;
            }
            UUID caseId = UUID.nameUUIDFromBytes(("reconciliation-case:"
                    + tenantId + ":" + result.resultId()).getBytes(StandardCharsets.UTF_8));
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("caseId", caseId.toString());
            payload.put("tenantId", tenantId.toString());
            payload.put("sourceCategory", "RECONCILIATION_DISCREPANCY");
            payload.put("sourceId", result.resultId().toString());
            if (result.relatedPaymentId() != null) {
                payload.put("paymentId", result.relatedPaymentId().toString());
            }
            payload.put("severity", severity(result.discrepancyCategory()));
            payload.put("dueAt", occurredAt.toString());
            payload.put("discrepancyCategory", result.discrepancyCategory().name());
            payload.put("runId", runId.toString());
            try {
                outbox.appendOrGet(new OutboxMessageDraft(
                        ProducerName.RECONCILIATION,
                        "case-request:RECONCILIATION_DISCREPANCY:" + result.resultId(),
                        "CreateCaseRequested", 1, caseId, tenantId,
                        "ledgerops.casework.commands.v1", caseId.toString(),
                        JSON.writeValueAsString(payload), runId, result.resultId(), occurredAt));
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot serialize Reconciliation Case command", exception);
            }
        }
    }

    static String severity(ReconciliationDiscrepancyCategory category) {
        return switch (category) {
            case MISSING_INTERNAL_PAYMENT, MISSING_INTERNAL_REVERSAL,
                    DUPLICATE_PROVIDER_RECORD, DUPLICATE_INTERNAL_REFERENCE,
                    AMOUNT_MISMATCH, LEDGER_TRANSACTION_MISSING,
                    LEDGER_AMOUNT_MISMATCH, REVERSAL_WITHOUT_PAYMENT_SETTLEMENT -> "CRITICAL";
            case MISSING_PROVIDER_RECORD, MISSING_PROVIDER_REVERSAL,
                    CURRENCY_MISMATCH, STATUS_MISMATCH,
                    UNRESOLVED_PROVIDER_REFERENCE, INVALID_PROVIDER_RECORD -> "HIGH";
            case SETTLEMENT_DATE_MISMATCH -> "MEDIUM";
        };
    }
}
