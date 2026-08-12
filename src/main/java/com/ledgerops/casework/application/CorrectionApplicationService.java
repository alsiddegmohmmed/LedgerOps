package com.ledgerops.casework.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.casework.api.CorrectionRequestCommand;
import com.ledgerops.casework.api.CorrectionRequestSnapshot;
import com.ledgerops.casework.domain.CaseFile;
import com.ledgerops.casework.domain.CaseSourceCategory;
import com.ledgerops.casework.domain.CorrectionRequest;
import com.ledgerops.casework.domain.CorrectionRequestStatus;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.SettlementCorrectionLedger;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerException;
import com.ledgerops.ledger.api.SettlementCorrectionRequest;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.reconciliation.api.ReconciliationCorrectionPort;
import com.ledgerops.reconciliation.api.SettlementCorrectionEligibility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
public class CorrectionApplicationService {

    private final CaseStore cases;
    private final CorrectionRequestStore corrections;
    private final ReconciliationCorrectionPort reconciliation;
    private final SettlementCorrectionLedger ledger;
    private final MessageOutbox outbox;
    private final AuditAppendPort audit;
    private final Clock clock;
    private final TransactionOperations transactions;

    @Autowired
    public CorrectionApplicationService(
            CaseStore cases,
            CorrectionRequestStore corrections,
            ReconciliationCorrectionPort reconciliation,
            SettlementCorrectionLedger ledger,
            MessageOutbox outbox,
            AuditAppendPort audit,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this(cases, corrections, reconciliation, ledger, outbox, audit, clock,
                new TransactionTemplate(Objects.requireNonNull(
                        transactionManager, "Transaction manager must not be null")));
    }

    CorrectionApplicationService(
            CaseStore cases,
            CorrectionRequestStore corrections,
            ReconciliationCorrectionPort reconciliation,
            SettlementCorrectionLedger ledger,
            MessageOutbox outbox,
            AuditAppendPort audit,
            Clock clock,
            TransactionOperations transactions
    ) {
        this.cases = cases;
        this.corrections = corrections;
        this.reconciliation = reconciliation;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public CorrectionRequestSnapshot request(CorrectionRequestCommand command) {
        CorrectionRequest candidate = CorrectionRequest.request(
                UUID.randomUUID(),
                command.tenantId(),
                command.caseId(),
                command.discrepancyId(),
                command.settlementPostingId(),
                command.originalLedgerTransactionId(),
                command.actorId(),
                command.reason(),
                clock.instant()
        );
        try {
            return requireResult(transactions.execute(
                    status -> executeCorrection(command, candidate)));
        } catch (SettlementCorrectionLedgerException exception) {
            return requireResult(transactions.execute(
                    status -> recordFailedCorrection(command, candidate, exception)));
        }
    }

    private CorrectionRequestSnapshot executeCorrection(
            CorrectionRequestCommand command,
            CorrectionRequest candidate
    ) {
        LockedCorrection locked = lockCorrection(command, candidate);
        CorrectionRequest stored = locked.correction();
        CaseFile current = locked.caseFile();
        if (stored.status() == CorrectionRequestStatus.COMPLETED) {
            requireCompletedEvidence(stored);
            return snapshot(stored);
        }

        if (ledger.findCompensationForTarget(
                command.tenantId(), command.originalLedgerTransactionId()).isPresent()) {
            throw new CorrectionRequestApplicationException(
                    "The original settlement adjustment already has a compensation");
        }

        CaseFile marked = markCorrectionRequested(current, stored, command);
        CorrectionRequest processing = stored.beginProcessing(clock.instant());
        corrections.save(processing);

        LedgerPostingEvidence evidence = ledger.postCompensation(
                new SettlementCorrectionRequest(
                        command.tenantId(), processing.correctionId(),
                        command.originalLedgerTransactionId()
                )
        );
        validateLedgerEvidence(processing, evidence);
        CorrectionRequest completed = processing.complete(
                evidence.transactionId(), clock.instant());
        corrections.save(completed);
        CaseFile completedCase = marked.completeCorrectiveAction(
                command.actorId(), command.reason(), clock.instant());
        cases.save(completedCase);
        appendEvent(completedCase, "CORRECTION_COMPLETED", command.correlationId());
        audit.appendAction(
                "application-user", command.actorId().toString(), "HUMAN",
                command.tenantId(), "case.correction-completed", "correction",
                completed.correctionId().toString(), command.reason(),
                "{\"originalLedgerTransactionId\":\""
                        + command.originalLedgerTransactionId()
                        + "\",\"compensationLedgerTransactionId\":\""
                        + evidence.transactionId() + "\"}",
                command.correlationId().toString()
        );
        return snapshot(completed);
    }

    private CorrectionRequestSnapshot recordFailedCorrection(
            CorrectionRequestCommand command,
            CorrectionRequest candidate,
            SettlementCorrectionLedgerException exception
    ) {
        LockedCorrection locked = lockCorrection(command, candidate);
        CorrectionRequest stored = locked.correction();
        if (stored.status() == CorrectionRequestStatus.COMPLETED) {
            requireCompletedEvidence(stored);
            return snapshot(stored);
        }

        markCorrectionRequested(locked.caseFile(), stored, command);
        CorrectionRequest processing = stored.status() == CorrectionRequestStatus.PROCESSING
                ? stored
                : stored.beginProcessing(clock.instant());
        corrections.save(processing);
        CorrectionRequest failed = processing.fail(
                exception.getMessage() == null
                        ? "Ledger correction failed"
                        : exception.getMessage(),
                clock.instant());
        corrections.save(failed);
        audit.appendAction(
                "application-user", command.actorId().toString(), "HUMAN",
                command.tenantId(), "case.correction-failed", "correction",
                failed.correctionId().toString(), failed.failureReason(), "{}",
                command.correlationId().toString()
        );
        return snapshot(failed);
    }

    private LockedCorrection lockCorrection(
            CorrectionRequestCommand command,
            CorrectionRequest candidate
    ) {
        SettlementCorrectionEligibility eligibility = reconciliation.lockAndCheck(
                command.tenantId(),
                command.discrepancyId(),
                command.settlementPostingId(),
                command.originalLedgerTransactionId()
        );
        if (!eligibility.discrepancyId().equals(command.discrepancyId())
                || !eligibility.settlementPostingId().equals(command.settlementPostingId())
                || !eligibility.originalLedgerTransactionId()
                .equals(command.originalLedgerTransactionId())) {
            throw new CorrectionRequestApplicationException(
                    "Reconciliation eligibility does not match the requested correction target");
        }

        CaseFile current = cases.lockByTenantAndId(command.tenantId(), command.caseId())
                .orElseThrow(() -> new CaseNotFoundException(command.caseId()));
        if (current.sourceCategory() != CaseSourceCategory.RECONCILIATION_DISCREPANCY
                || !current.sourceId().equals(command.discrepancyId())) {
            throw new CorrectionRequestApplicationException(
                    "Correction Case does not own the requested discrepancy");
        }

        CorrectionRequest inserted = corrections.insertIfAbsent(candidate);
        CorrectionRequest stored = corrections.lockByTenantAndId(
                        command.tenantId(), inserted.correctionId())
                .orElseThrow(() -> new CorrectionRequestApplicationException(
                        "Correction request disappeared before it could be locked"));
        validateIdentity(stored, candidate);
        return new LockedCorrection(current, stored);
    }

    private CaseFile markCorrectionRequested(
            CaseFile current,
            CorrectionRequest stored,
            CorrectionRequestCommand command
    ) {
        CaseFile marked = current.requireCorrectiveAction(
                command.actorId(), command.reason(), clock.instant());
        cases.save(marked);
        if (!current.correctiveActionRequired()) {
            appendEvent(marked, "CORRECTION_REQUESTED", command.correlationId());
            audit.appendAction(
                    "application-user", command.actorId().toString(), "HUMAN",
                    command.tenantId(), "case.correction-requested", "correction",
                    stored.correctionId().toString(), command.reason(),
                    "{\"discrepancyId\":\"" + command.discrepancyId()
                            + "\",\"settlementPostingId\":\""
                            + command.settlementPostingId()
                            + "\",\"originalLedgerTransactionId\":\""
                            + command.originalLedgerTransactionId() + "\"}",
                    command.correlationId().toString()
            );
        }
        return marked;
    }

    private void requireCompletedEvidence(CorrectionRequest request) {
        LedgerPostingEvidence evidence = ledger.findByCorrectionSource(
                request.tenantId(), request.correctionId()).orElseThrow(
                () -> new CorrectionRequestApplicationException(
                        "Completed CorrectionRequest has no Ledger compensation evidence"));
        validateLedgerEvidence(request, evidence);
        if (!evidence.transactionId().equals(request.compensationLedgerTransactionId())) {
            throw new CorrectionRequestApplicationException(
                    "Completed CorrectionRequest references different Ledger evidence");
        }
    }

    private void validateLedgerEvidence(
            CorrectionRequest request,
            LedgerPostingEvidence evidence
    ) {
        if (!evidence.tenantId().equals(request.tenantId())
                || !"AUTHORISED_CORRECTION".equals(evidence.sourceType())
                || !evidence.sourceId().equals(request.correctionId())
                || evidence.compensatesTransactionId()
                .filter(request.originalLedgerTransactionId()::equals)
                .isEmpty()) {
            throw new CorrectionRequestApplicationException(
                    "Ledger correction evidence does not match the CorrectionRequest");
        }
    }

    private CorrectionRequestSnapshot requireResult(CorrectionRequestSnapshot result) {
        return Objects.requireNonNull(result, "Correction transaction returned no result");
    }

    private void validateIdentity(CorrectionRequest actual, CorrectionRequest expected) {
        if (!actual.tenantId().equals(expected.tenantId())
                || !actual.caseId().equals(expected.caseId())
                || !actual.discrepancyId().equals(expected.discrepancyId())
                || !actual.settlementPostingInstructionId()
                .equals(expected.settlementPostingInstructionId())
                || !actual.originalLedgerTransactionId()
                .equals(expected.originalLedgerTransactionId())
                || !actual.kind().equals(expected.kind())
                || !actual.requestedBy().equals(expected.requestedBy())
                || !actual.reason().equals(expected.reason())) {
            throw new CorrectionRequestApplicationException(
                    "Existing correction target has different immutable identity");
        }
    }

    private void appendEvent(CaseFile file, String eventType, UUID causationId) {
        outbox.appendOrGet(CaseLifecycleEventFactory.draft(
                file,
                eventType,
                clock.instant(),
                UUID.nameUUIDFromBytes(("case-correlation:" + file.caseId() + ":"
                        + file.history().size()).getBytes(StandardCharsets.UTF_8)),
                causationId
        ));
    }

    private CorrectionRequestSnapshot snapshot(CorrectionRequest request) {
        return new CorrectionRequestSnapshot(
                request.correctionId(), request.tenantId(), request.caseId(),
                request.discrepancyId(), request.settlementPostingInstructionId(),
                request.originalLedgerTransactionId(), request.kind(), request.requestedBy(),
                request.reason(), request.requestedAt(), request.status(), request.updatedAt(),
                request.compensationLedgerTransactionId(), request.failureReason()
        );
    }

    private record LockedCorrection(CaseFile caseFile, CorrectionRequest correction) {
    }
}
