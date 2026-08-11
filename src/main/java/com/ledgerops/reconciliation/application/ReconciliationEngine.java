package com.ledgerops.reconciliation.application;

import com.ledgerops.ledger.api.LedgerSettlementEvidence;
import com.ledgerops.ledger.api.LedgerSettlementEvidenceQuery;
import com.ledgerops.ledger.api.LedgerSettlementSource;
import com.ledgerops.ledger.api.LedgerSettlementSourceType;
import com.ledgerops.payment.api.PaymentReconciliationPage;
import com.ledgerops.payment.api.PaymentReconciliationPageRequest;
import com.ledgerops.payment.api.PaymentReconciliationQuery;
import com.ledgerops.payment.api.PaymentReconciliationSubject;
import com.ledgerops.payment.api.ReconciliationSubjectType;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderEvidenceBatchQuery;
import com.ledgerops.reconciliation.api.SettlementBatchSnapshot;
import com.ledgerops.reconciliation.domain.ReconciliationDiscrepancyCategory;
import com.ledgerops.reconciliation.domain.ReconciliationRun;
import com.ledgerops.reconciliation.domain.ReconciliationRunCounts;
import com.ledgerops.reconciliation.domain.ReconciliationRunStatus;
import com.ledgerops.reconciliation.domain.SettlementBatchStatus;
import com.ledgerops.reconciliation.domain.SettlementRecord;
import com.ledgerops.reconciliation.domain.SettlementOperationType;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ReconciliationEngine {

    private static final int PAGE_SIZE = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SettlementBatchStore batches;
    private final PaymentReconciliationQuery paymentSubjects;
    private final ProviderEvidenceBatchQuery providerEvidence;
    private final LedgerSettlementEvidenceQuery ledgerEvidence;
    private final ReconciliationSnapshotStore snapshots;
    private final ReconciliationCaseCommandService caseCommands;
    private final Clock clock;

    public ReconciliationEngine(
            SettlementBatchStore batches,
            PaymentReconciliationQuery paymentSubjects,
            ProviderEvidenceBatchQuery providerEvidence,
            LedgerSettlementEvidenceQuery ledgerEvidence,
            ReconciliationSnapshotStore snapshots,
            ReconciliationCaseCommandService caseCommands,
            Clock clock
    ) {
        this.batches = Objects.requireNonNull(batches, "Settlement batch store must not be null");
        this.paymentSubjects = Objects.requireNonNull(
                paymentSubjects, "Payment reconciliation query must not be null");
        this.providerEvidence = Objects.requireNonNull(
                providerEvidence, "Provider evidence query must not be null");
        this.ledgerEvidence = Objects.requireNonNull(
                ledgerEvidence, "Ledger evidence query must not be null");
        this.snapshots = Objects.requireNonNull(
                snapshots, "Reconciliation snapshot store must not be null");
        this.caseCommands = Objects.requireNonNull(
                caseCommands, "Reconciliation Case command service must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public ReconciliationRunExecution execute(ExecuteCommand command) {
        Objects.requireNonNull(command, "Reconciliation command must not be null");
        SettlementBatchSnapshot batch = batches.findById(command.tenantId(), command.batchVersionId())
                .orElseThrow(() -> new IllegalArgumentException("Settlement batch does not exist"));
        if (batch.status() != SettlementBatchStatus.COMPLETED
                && batch.status() != SettlementBatchStatus.COMPLETED_WITH_DISCREPANCIES) {
            throw new IllegalStateException("Settlement batch must be completed before reconciliation");
        }

        Instant now = clock.instant();
        ReconciliationSnapshotStore.SnapshotIdentity snapshot = snapshots.createBuildingSnapshot(
                command.tenantId(), batch.familyId(), batch.batchVersionId(),
                command.rulesVersion(), command.sourceCutoff(), now);
        MessageDigest digest = sha256();
        long recordCount = 0;
        long factCount = 0;
        try {
            recordCount = captureSettlementRecords(snapshot, digest);
            factCount = captureFinancialSubjects(snapshot, digest);
            snapshots.completeSnapshot(
                    snapshot.snapshotId(), HexFormat.of().formatHex(digest.digest()),
                    recordCount, factCount, clock.instant());
        } catch (RuntimeException exception) {
            snapshots.failSnapshot(snapshot.snapshotId(), safeFailure(exception), clock.instant());
            throw exception;
        }

        UUID runId = snapshots.createQueuedRun(snapshot, clock.instant());
        ReconciliationRun run = ReconciliationRun.queued(
                runId, snapshot.tenantId(), snapshot.batchFamilyId(), snapshot.batchVersionId(),
                snapshot.snapshotId(), snapshot.runNumber(), snapshot.rulesVersion(),
                snapshot.sourceCutoff(), now);
        Instant startedAt = clock.instant();
        snapshots.startRun(runId, startedAt);
        run.start(startedAt);
        try {
            snapshots.seedPendingStatuses(snapshot.tenantId(), runId, snapshot.snapshotId(), clock.instant());
            snapshots.seedAwaitingBatchStatuses(
                    snapshot.tenantId(), runId, snapshot.snapshotId(), clock.instant());
            ReconciliationRunCounts counts = reconcile(runId, snapshot);
            Instant completedAt = clock.instant();
            run.complete(counts, completedAt);
            snapshots.completeRun(runId, counts.matchedCount(), counts.unmatchedCount(),
                    counts.discrepancyCount(), completedAt);
            return new ReconciliationRunExecution(
                    run.runId(), run.snapshotId(), run.runNumber(), run.status(), counts);
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();
            run.fail(safeFailure(exception), failedAt);
            snapshots.failRun(runId, safeFailure(exception), failedAt);
            throw exception;
        }
    }

    public ReconciliationSnapshotStore.CurrentRun promote(PromoteCommand command, Instant promotedAt) {
        Objects.requireNonNull(command, "Promotion command must not be null");
        snapshots.promoteCurrentRun(
                command.tenantId(), command.batchFamilyId(), command.runId(), promotedAt);
        return snapshots.findCurrentRun(command.tenantId(), command.batchFamilyId()).orElseThrow(
                () -> new IllegalStateException("Current reconciliation run was not persisted"));
    }

    private long captureSettlementRecords(
            ReconciliationSnapshotStore.SnapshotIdentity snapshot,
            MessageDigest digest
    ) {
        long count = 0;
        for (int page = 0; ; page++) {
            List<SettlementBatchStore.SettlementOccurrenceRow> rows =
                    batches.readOccurrences(snapshot.batchVersionId(), page, PAGE_SIZE);
            if (rows.isEmpty()) {
                return count;
            }
            List<ReconciliationSnapshotOccurrence> captured = new ArrayList<>(rows.size());
            for (SettlementBatchStore.SettlementOccurrenceRow row : rows) {
                if (row.validationState().equals("VALID") && row.canonicalRecordVersionId() == null) {
                    throw new IllegalStateException(
                            "Settlement occurrence is not canonicalized: " + row.occurrenceId());
                }
                captured.add(new ReconciliationSnapshotOccurrence(
                        snapshot.snapshotId(), row.tenantId(), row.batchVersionId(),
                        row.occurrenceId(), row.canonicalRecordVersionId(), row.rowNumber(),
                        row.providerRecordKey(), row.normalizedContentHash(), row.normalizedContent(),
                        row.validationState(), row.reasonCode()));
                digestLine(digest, "O|" + row.rowNumber() + "|" + row.normalizedContentHash()
                        + "|" + row.normalizedContent() + "|" + row.validationState()
                        + "|" + row.reasonCode());
            }
            snapshots.insertSettlementRecords(
                    snapshot.snapshotId(), snapshot.tenantId(), snapshot.batchVersionId(),
                    captured, clock.instant());
            count += rows.size();
        }
    }

    private long captureFinancialSubjects(
            ReconciliationSnapshotStore.SnapshotIdentity snapshot,
            MessageDigest digest
    ) {
        long count = 0;
        com.ledgerops.payment.api.ReconciliationSubjectCursor cursor = null;
        while (true) {
            PaymentReconciliationPage page = paymentSubjects.findPage(
                    new PaymentReconciliationPageRequest(
                            snapshot.tenantId(), snapshot.sourceCutoff(), PAGE_SIZE, cursor));
            if (page.subjects().isEmpty()) {
                return count;
            }
            List<UUID> evidenceIds = page.subjects().stream()
                    .map(PaymentReconciliationSubject::providerEvidenceId)
                    .toList();
            Map<UUID, ProviderEvidence> providerByEvidence = providerEvidence
                    .findByTenantAndEvidenceIds(snapshot.tenantId(), evidenceIds);
            List<LedgerSettlementSource> sources = page.subjects().stream()
                    .map(this::ledgerSource)
                    .toList();
            Map<LedgerSettlementSource, LedgerSettlementEvidence> ledgerBySource = ledgerEvidence
                    .findBySources(snapshot.tenantId(), sources);
            List<ReconciliationSnapshotSubject> captured = new ArrayList<>(page.subjects().size());
            for (PaymentReconciliationSubject subject : page.subjects()) {
                ProviderEvidence provider = providerByEvidence.get(subject.providerEvidenceId());
                LedgerSettlementEvidence ledger = ledgerBySource.get(ledgerSource(subject));
                captured.add(new ReconciliationSnapshotSubject(subject, provider, ledger));
                digestLine(digest, "F|" + subject.subjectType() + "|" + subject.subjectId()
                        + "|" + subject.amount().toPlainString() + "|"
                        + subject.currency().getCurrencyCode() + "|" + subject.providerIdempotencyKey()
                        + "|" + subject.providerReference() + "|" + subject.appliedAt()
                        + "|" + json(provider) + "|" + json(ledger));
            }
            snapshots.insertFinancialSubjects(
                    snapshot.snapshotId(), snapshot.tenantId(), snapshot.batchVersionId(),
                    captured, clock.instant());
            count += captured.size();
            cursor = page.nextCursor().orElse(null);
            if (cursor == null) {
                return count;
            }
        }
    }

    private ReconciliationRunCounts reconcile(
            UUID runId,
            ReconciliationSnapshotStore.SnapshotIdentity snapshot
    ) {
        long matched = 0;
        long unmatched = 0;
        long discrepancies = 0;
        java.util.Set<UUID> exactPaymentIds = exactPaymentIds(snapshot);
        for (int page = 0; ; page++) {
            List<ReconciliationSnapshotOccurrence> occurrences =
                    snapshots.readOccurrences(snapshot.snapshotId(), page, PAGE_SIZE);
            if (occurrences.isEmpty()) {
                ReconciliationRunCounts missingProvider = reconcileSubjectsWithoutSettlementRecord(
                        runId, snapshot, matched, unmatched, discrepancies);
                return missingProvider;
            }
            List<ParsedOccurrence> parsed = occurrences.stream()
                    .map(this::parseOccurrence)
                    .toList();
            List<ReconciliationSnapshotStore.SubjectKey> keys = parsed.stream()
                    .map(ParsedOccurrence::key)
                    .filter(Objects::nonNull)
                    .toList();
            Map<ReconciliationSnapshotStore.SubjectKey,
                    ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow> subjects =
                    snapshots.findSubjects(snapshot.snapshotId(), keys);
            Map<String, List<ReconciliationSnapshotStore.SubjectKey>> subjectsByReference =
                    snapshots.findSubjectKeysByProviderReferences(snapshot.snapshotId(), parsed.stream()
                            .map(ParsedOccurrence::record)
                            .filter(Objects::nonNull)
                            .map(SettlementRecord::providerReference)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList());
            List<ReconciliationResultDraft> results = new ArrayList<>(parsed.size());
            List<ReconciliationSnapshotStore.SubjectStatusDraft> statuses =
                    new ArrayList<>(parsed.size());
            for (ParsedOccurrence value : parsed) {
                ReconciliationSnapshotStore.SubjectKey key = value.key();
                ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subject = key == null
                        ? null : subjects.get(key);
                ReconciliationDiscrepancyCategory category = value.preclassifiedCategory() != null
                        ? value.preclassifiedCategory()
                        : duplicateInternalReference(value.record(), subjectsByReference)
                        ? ReconciliationDiscrepancyCategory.DUPLICATE_INTERNAL_REFERENCE
                        : discrepancy(value.record(), subject, exactPaymentIds);
                String resultStatus = category == null ? "MATCHED" : "DISCREPANCY";
                UUID resultId = UUID.nameUUIDFromBytes(("reconciliation-result:"
                        + runId + ":" + value.occurrence().occurrenceId())
                        .getBytes(StandardCharsets.UTF_8));
                results.add(new ReconciliationResultDraft(
                        resultId,
                        value.occurrence().occurrenceId(),
                        value.occurrence().canonicalRecordVersionId(),
                        key == null ? null : key.subjectType(), key == null ? null : key.subjectId(),
                        subject == null ? null : subject.paymentId(),
                        resultStatus, category,
                        value.record() == null ? Map.of() : providerValues(value.record()),
                        internalValues(subject)));
                if (key != null) {
                    statuses.add(new ReconciliationSnapshotStore.SubjectStatusDraft(
                            key.subjectType(), key.subjectId(),
                            category == null ? "MATCHED" : "DISCREPANCY"));
                }
                if (category == null) matched++;
                else {
                    if (isMissingSide(category)) unmatched++;
                    discrepancies++;
                }
            }
            snapshots.insertResults(runId, snapshot.tenantId(), results, clock.instant());
            caseCommands.publishDiscrepancyCommands(
                    snapshot.tenantId(), runId, results, clock.instant());
            snapshots.appendSubjectStatuses(snapshot.tenantId(), runId, statuses, clock.instant());
        }
    }

    private java.util.Set<UUID> exactPaymentIds(
            ReconciliationSnapshotStore.SnapshotIdentity snapshot
    ) {
        java.util.Set<UUID> exactPaymentIds = new java.util.HashSet<>();
        for (int page = 0; ; page++) {
            List<ReconciliationSnapshotOccurrence> occurrences =
                    snapshots.readOccurrences(snapshot.snapshotId(), page, PAGE_SIZE);
            if (occurrences.isEmpty()) {
                return exactPaymentIds;
            }
            List<ParsedOccurrence> parsed = occurrences.stream()
                    .map(this::parseOccurrence)
                    .toList();
            List<ReconciliationSnapshotStore.SubjectKey> keys = parsed.stream()
                    .map(ParsedOccurrence::key)
                    .filter(Objects::nonNull)
                    .toList();
            Map<ReconciliationSnapshotStore.SubjectKey,
                    ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow> subjects =
                    snapshots.findSubjects(snapshot.snapshotId(), keys);
            Map<String, List<ReconciliationSnapshotStore.SubjectKey>> subjectsByReference =
                    snapshots.findSubjectKeysByProviderReferences(snapshot.snapshotId(), parsed.stream()
                            .map(ParsedOccurrence::record)
                            .filter(Objects::nonNull)
                            .map(SettlementRecord::providerReference)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList());
            for (ParsedOccurrence value : parsed) {
                if (value.preclassifiedCategory() != null
                        || value.record() == null
                        || value.record().operationType() != SettlementOperationType.PAYMENT
                        || duplicateInternalReference(value.record(), subjectsByReference)) {
                    continue;
                }
                ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subject =
                        subjects.get(value.key());
                if (baseDiscrepancy(value.record(), subject) == null && subject != null) {
                    exactPaymentIds.add(subject.paymentId());
                }
            }
        }
    }

    private ReconciliationRunCounts reconcileSubjectsWithoutSettlementRecord(
            UUID runId,
            ReconciliationSnapshotStore.SnapshotIdentity snapshot,
            long matched,
            long unmatched,
            long discrepancies
    ) {
        for (int page = 0; ; page++) {
            List<ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow> subjects =
                    snapshots.findSubjectsWithoutSettlementRecord(snapshot.snapshotId(), page, PAGE_SIZE);
            if (subjects.isEmpty()) {
                return new ReconciliationRunCounts(matched, unmatched, discrepancies);
            }
            List<ReconciliationResultDraft> results = new ArrayList<>(subjects.size());
            List<ReconciliationSnapshotStore.SubjectStatusDraft> statuses =
                    new ArrayList<>(subjects.size());
            for (ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subject : subjects) {
                ReconciliationDiscrepancyCategory category =
                        subject.key().subjectType().equals(ReconciliationSubjectType.PAYMENT.name())
                                ? ReconciliationDiscrepancyCategory.MISSING_PROVIDER_RECORD
                                : ReconciliationDiscrepancyCategory.MISSING_PROVIDER_REVERSAL;
                UUID resultId = UUID.nameUUIDFromBytes(("reconciliation-result:"
                        + runId + ":SUBJECT:" + subject.key().subjectType() + ":"
                        + subject.key().subjectId()).getBytes(StandardCharsets.UTF_8));
                results.add(new ReconciliationResultDraft(
                        resultId,
                        null,
                        null,
                        subject.key().subjectType(),
                        subject.key().subjectId(),
                        subject.paymentId(),
                        "DISCREPANCY",
                        category,
                        Map.of(),
                        internalValues(subject)));
                statuses.add(new ReconciliationSnapshotStore.SubjectStatusDraft(
                        subject.key().subjectType(), subject.key().subjectId(), "DISCREPANCY"));
            }
            snapshots.insertResults(runId, snapshot.tenantId(), results, clock.instant());
            caseCommands.publishDiscrepancyCommands(
                    snapshot.tenantId(), runId, results, clock.instant());
            snapshots.appendSubjectStatuses(snapshot.tenantId(), runId, statuses, clock.instant());
            unmatched += subjects.size();
            discrepancies += subjects.size();
        }
    }

    private boolean isMissingSide(ReconciliationDiscrepancyCategory category) {
        return switch (category) {
            case MISSING_INTERNAL_PAYMENT, MISSING_PROVIDER_RECORD,
                    MISSING_INTERNAL_REVERSAL, MISSING_PROVIDER_REVERSAL -> true;
            default -> false;
        };
    }

    private boolean duplicateInternalReference(
            SettlementRecord record,
            Map<String, List<ReconciliationSnapshotStore.SubjectKey>> subjectsByReference
    ) {
        if (record == null || record.providerReference() == null) {
            return false;
        }
        List<ReconciliationSnapshotStore.SubjectKey> matches =
                subjectsByReference.get(record.providerReference());
        return matches != null && matches.size() > 1;
    }

    private ParsedOccurrence parseOccurrence(ReconciliationSnapshotOccurrence occurrence) {
        if (occurrence.validationState().equals("QUARANTINED")
                && occurrence.reasonCode() != null
                && !occurrence.reasonCode().equals("DUPLICATE_PROVIDER_RECORD")
                && !occurrence.reasonCode().equals("CONFLICTING_PROVIDER_RECORD")) {
            return new ParsedOccurrence(
                    occurrence, null, ReconciliationDiscrepancyCategory.INVALID_PROVIDER_RECORD);
        }
        try {
            JsonNode node = JSON.readTree(occurrence.normalizedContent());
            return new ParsedOccurrence(occurrence, SettlementRecord.fromFields(List.of(
                    text(node, "providerBatchReference"),
                    text(node, "settlementPeriodStart"),
                    text(node, "settlementPeriodEnd"),
                    text(node, "providerRecordKey"),
                    text(node, "operationType"),
                    text(node, "providerIdempotencyKey"),
                    text(node, "providerReference"),
                    text(node, "amount"),
                    text(node, "currency"),
                    text(node, "transactionStatus"),
                    text(node, "settlementDate"),
                    text(node, "providerEventTime"))),
                    preclassified(occurrence.reasonCode()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Canonical settlement occurrence could not be parsed: " + occurrence.occurrenceId(),
                    exception);
        }
    }

    private ReconciliationDiscrepancyCategory discrepancy(
            SettlementRecord record,
            ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subject,
            java.util.Set<UUID> exactPaymentIds
    ) {
        ReconciliationDiscrepancyCategory base = baseDiscrepancy(record, subject);
        if (base != null) {
            return base;
        }
        if (record.operationType() == SettlementOperationType.REVERSAL
                && !exactPaymentIds.contains(subject.paymentId())) {
            return ReconciliationDiscrepancyCategory.REVERSAL_WITHOUT_PAYMENT_SETTLEMENT;
        }
        return null;
    }

    private ReconciliationDiscrepancyCategory baseDiscrepancy(
            SettlementRecord record,
            ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subject
    ) {
        if (subject == null) {
            return record.operationType() == SettlementOperationType.PAYMENT
                    ? ReconciliationDiscrepancyCategory.MISSING_INTERNAL_PAYMENT
                    : ReconciliationDiscrepancyCategory.MISSING_INTERNAL_REVERSAL;
        }
        if (subject.providerResultCategory() == null) {
            return record.operationType() == SettlementOperationType.PAYMENT
                    ? ReconciliationDiscrepancyCategory.MISSING_PROVIDER_RECORD
                    : ReconciliationDiscrepancyCategory.MISSING_PROVIDER_REVERSAL;
        }
        if (!subject.providerIdempotencyKey().equals(record.providerIdempotencyKey())
                || !Objects.equals(subject.providerReference(), record.providerReference())) {
            return ReconciliationDiscrepancyCategory.UNRESOLVED_PROVIDER_REFERENCE;
        }
        if (subject.amount().compareTo(record.amount()) != 0) {
            return ReconciliationDiscrepancyCategory.AMOUNT_MISMATCH;
        }
        if (!subject.currency().getCurrencyCode().equals(record.currency())) {
            return ReconciliationDiscrepancyCategory.CURRENCY_MISMATCH;
        }
        String expectedStatus = record.operationType() == SettlementOperationType.PAYMENT
                ? "SUCCESS" : "REVERSED";
        if (!expectedStatus.equals(record.transactionStatus().name())
                || !"SUCCESS".equals(subject.providerResultCategory())
                || !"COMPLETED".equals(subject.financialStatus())) {
            return ReconciliationDiscrepancyCategory.STATUS_MISMATCH;
        }
        if (subject.ledgerTransactionId() == null || subject.ledgerEntriesJson() == null) {
            return ReconciliationDiscrepancyCategory.LEDGER_TRANSACTION_MISSING;
        }
        if (!ledgerMatches(record, subject)) {
            return ReconciliationDiscrepancyCategory.LEDGER_AMOUNT_MISMATCH;
        }
        if (subject.ledgerPostedAt() == null
                || !record.settlementDate().equals(
                subject.ledgerPostedAt().atZone(ZoneOffset.UTC).toLocalDate())) {
            return ReconciliationDiscrepancyCategory.SETTLEMENT_DATE_MISMATCH;
        }
        return null;
    }

    private boolean ledgerMatches(
            SettlementRecord record,
            ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subject
    ) {
        if (subject.ledgerTotalDebits() == null || subject.ledgerTotalCredits() == null
                || subject.ledgerTotalDebits().compareTo(record.amount()) != 0
                || subject.ledgerTotalCredits().compareTo(record.amount()) != 0) {
            return false;
        }
        try {
            JsonNode entries = JSON.readTree(subject.ledgerEntriesJson());
            if (!entries.isArray() || entries.size() != 2) {
                return false;
            }
            String debitCode = record.operationType() == SettlementOperationType.PAYMENT
                    ? "PROVIDER_CLEARING" : "MERCHANT_PAYABLE";
            String creditCode = record.operationType() == SettlementOperationType.PAYMENT
                    ? "MERCHANT_PAYABLE" : "PROVIDER_CLEARING";
            return hasEntry(entries, debitCode, "DEBIT", record)
                    && hasEntry(entries, creditCode, "CREDIT", record);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean hasEntry(JsonNode entries, String accountCode, String direction, SettlementRecord record) {
        for (JsonNode entry : entries) {
            if (accountCode.equals(text(entry, "accountCode"))
                    && direction.equals(text(entry, "direction"))
                    && record.currency().equals(text(entry, "currency"))
                    && new BigDecimal(text(entry, "amount")).compareTo(record.amount()) == 0) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> providerValues(SettlementRecord record) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("providerRecordKey", record.providerRecordKey());
        values.put("providerIdempotencyKey", record.providerIdempotencyKey());
        values.put("providerReference", record.providerReference());
        values.put("operationType", record.operationType().name());
        values.put("amount", record.amount().toPlainString());
        values.put("currency", record.currency());
        values.put("transactionStatus", record.transactionStatus().name());
        values.put("settlementDate", record.settlementDate().toString());
        values.put("providerEventTime", record.providerEventTime().toString());
        return values;
    }

    private Map<String, Object> internalValues(
            ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subject
    ) {
        if (subject == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("subjectType", subject.key().subjectType());
        values.put("subjectId", subject.key().subjectId().toString());
        values.put("amount", subject.amount().toPlainString());
        values.put("currency", subject.currency().getCurrencyCode());
        values.put("providerReference", subject.providerReference());
        values.put("providerResultCategory", subject.providerResultCategory());
        values.put("financialStatus", subject.financialStatus());
        values.put("ledgerTransactionId", subject.ledgerTransactionId());
        values.put("ledgerPostedAt", subject.ledgerPostedAt());
        return values;
    }

    private LedgerSettlementSource ledgerSource(PaymentReconciliationSubject subject) {
        return new LedgerSettlementSource(
                subject.subjectType() == ReconciliationSubjectType.PAYMENT
                        ? LedgerSettlementSourceType.PAYMENT
                        : LedgerSettlementSourceType.REVERSAL,
                subject.subjectId());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Missing settlement field: " + field);
        }
        return value.asText();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void digestLine(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private static String json(Object value) {
        try {
            return value == null ? "{}" : JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Reconciliation evidence could not be encoded", exception);
        }
    }

    private static String safeFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 2_500 ? message.substring(0, 2_500) : message;
    }

    private static ReconciliationDiscrepancyCategory preclassified(String reasonCode) {
        if (reasonCode == null) {
            return null;
        }
        return switch (reasonCode) {
            case "DUPLICATE_PROVIDER_RECORD" -> ReconciliationDiscrepancyCategory.DUPLICATE_PROVIDER_RECORD;
            case "CONFLICTING_PROVIDER_RECORD" -> ReconciliationDiscrepancyCategory.INVALID_PROVIDER_RECORD;
            default -> null;
        };
    }

    public record ExecuteCommand(
            UUID tenantId,
            UUID batchVersionId,
            String rulesVersion,
            Instant sourceCutoff
    ) {

        public ExecuteCommand {
            Objects.requireNonNull(tenantId, "Tenant ID must not be null");
            Objects.requireNonNull(batchVersionId, "Batch version ID must not be null");
            Objects.requireNonNull(rulesVersion, "Rules version must not be null");
            Objects.requireNonNull(sourceCutoff, "Source cutoff must not be null");
        }
    }

    public record PromoteCommand(
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId
    ) {

        public PromoteCommand {
            Objects.requireNonNull(tenantId, "Tenant ID must not be null");
            Objects.requireNonNull(batchFamilyId, "Batch family ID must not be null");
            Objects.requireNonNull(runId, "Run ID must not be null");
        }
    }

    private record ParsedOccurrence(
            ReconciliationSnapshotOccurrence occurrence,
            SettlementRecord record,
            ReconciliationDiscrepancyCategory preclassifiedCategory
    ) {

        private ReconciliationSnapshotStore.SubjectKey key() {
            return record == null ? null : new ReconciliationSnapshotStore.SubjectKey(
                    record.operationType().name(), record.subjectId());
        }
    }
}
