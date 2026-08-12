package com.ledgerops.ledger.application;

import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.SettlementCorrectionLedger;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerError;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerException;
import com.ledgerops.ledger.api.SettlementCorrectionRequest;
import com.ledgerops.ledger.domain.LedgerEntry;
import com.ledgerops.ledger.domain.LedgerEntryDirection;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerSourceType;
import com.ledgerops.ledger.domain.LedgerTransaction;
import com.ledgerops.ledger.domain.LedgerTransactionId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementCorrectionLedgerService implements SettlementCorrectionLedger {

    private final LedgerTransactionStore transactions;
    private final LedgerPostingService posting;
    private final Clock clock;

    public SettlementCorrectionLedgerService(
            LedgerTransactionStore transactions,
            LedgerPostingService posting,
            Clock clock
    ) {
        this.transactions = transactions;
        this.posting = posting;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerPostingEvidence> findByCorrectionSource(
            UUID tenantId,
            UUID correctionId
    ) {
        return transactions.findBySource(
                tenantId,
                LedgerSourceType.AUTHORISED_CORRECTION,
                correctionId
        ).map(this::toEvidence);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerPostingEvidence> findCompensationForTarget(
            UUID tenantId,
            UUID originalTransactionId
    ) {
        return transactions.findCompensationForTarget(
                tenantId,
                originalTransactionId
        ).map(this::toEvidence);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerPostingEvidence postCompensation(SettlementCorrectionRequest request) {
        Objects.requireNonNull(request, "Settlement correction request must not be null");

        LedgerTransaction original = transactions.lockById(
                request.tenantId(),
                request.originalTransactionId()
        ).orElseThrow(() -> new SettlementCorrectionLedgerException(
                SettlementCorrectionLedgerError.ORIGINAL_TRANSACTION_NOT_FOUND,
                "The original Ledger transaction does not exist for the tenant"
        ));
        validateOriginal(original, request);

        Optional<LedgerPostingEvidence> existing = findByCorrectionSource(
                request.tenantId(),
                request.correctionId()
        );
        if (existing.isPresent()) {
            validateReplay(existing.orElseThrow(), original, request);
            return existing.orElseThrow();
        }

        Optional<LedgerPostingEvidence> targetCompensation = findCompensationForTarget(
                request.tenantId(),
                request.originalTransactionId()
        );
        if (targetCompensation.isPresent()) {
            throw new SettlementCorrectionLedgerException(
                    SettlementCorrectionLedgerError.TARGET_ALREADY_COMPENSATED,
                    "The original settlement adjustment already has a compensation"
            );
        }

        LedgerTransaction compensation = LedgerTransaction.postCompensation(
                LedgerTransactionId.newId(),
                request.tenantId(),
                new LedgerSourceReference(
                        request.tenantId(),
                        LedgerSourceType.AUTHORISED_CORRECTION,
                        request.correctionId()
                ),
                original.id(),
                clock.instant(),
                inverseEntries(original)
        );

        try {
            return toEvidence(posting.post(compensation));
        } catch (DuplicateKeyException exception) {
            LedgerPostingEvidence raced = findByCorrectionSource(
                    request.tenantId(),
                    request.correctionId()
            ).orElseThrow(() -> new SettlementCorrectionLedgerException(
                    SettlementCorrectionLedgerError.SOURCE_ALREADY_POSTED,
                    "Correction posting conflicted but could not be reloaded",
                    exception
            ));
            validateReplay(raced, original, request);
            return raced;
        } catch (LedgerPostingException exception) {
            throw new SettlementCorrectionLedgerException(
                    SettlementCorrectionLedgerError.POSTING_FAILED,
                    "The Ledger rejected the settlement compensation posting",
                    exception
            );
        }
    }

    private void validateOriginal(
            LedgerTransaction original,
            SettlementCorrectionRequest request
    ) {
        if (!original.tenantId().equals(request.tenantId())
                || original.sourceReference().sourceType()
                != LedgerSourceType.SETTLEMENT_ADJUSTMENT
                || original.isCompensating()) {
            throw new SettlementCorrectionLedgerException(
                    SettlementCorrectionLedgerError.ORIGINAL_TRANSACTION_NOT_SETTLEMENT_ADJUSTMENT,
                    "Only an uncompensated settlement adjustment may be corrected"
            );
        }
    }

    private List<LedgerEntry> inverseEntries(LedgerTransaction original) {
        return original.entries().stream()
                .map(entry -> entry.direction() == LedgerEntryDirection.DEBIT
                        ? LedgerEntry.credit(entry.account(), entry.amount())
                        : LedgerEntry.debit(entry.account(), entry.amount()))
                .toList();
    }

    private void validateReplay(
            LedgerPostingEvidence evidence,
            LedgerTransaction original,
            SettlementCorrectionRequest request
    ) {
        boolean valid = evidence.tenantId().equals(request.tenantId())
                && evidence.sourceType().equals(LedgerSourceType.AUTHORISED_CORRECTION.name())
                && evidence.sourceId().equals(request.correctionId())
                && evidence.compensatesTransactionId()
                .filter(request.originalTransactionId()::equals)
                .isPresent()
                && evidence.currency().equals(original.currency())
                && evidence.totalDebits().compareTo(original.totalCredits()) == 0
                && evidence.totalCredits().compareTo(original.totalDebits()) == 0
                && evidence.entries().equals(inverseEntries(original).stream()
                .map(entry -> new LedgerPostingEntryEvidence(
                        entry.account().accountId().value(),
                        entry.account().accountCode().name(),
                        entry.direction().name(),
                        entry.amount().amount(),
                        entry.amount().currency()
                )).toList());
        if (!valid) {
            throw new SettlementCorrectionLedgerException(
                    SettlementCorrectionLedgerError.SOURCE_POSTING_MISMATCH,
                    "Existing correction source has different immutable Ledger evidence"
            );
        }
    }

    private LedgerPostingEvidence toEvidence(LedgerTransaction transaction) {
        return new LedgerPostingEvidence(
                transaction.id().value(),
                transaction.tenantId(),
                transaction.sourceReference().sourceType().name(),
                transaction.sourceReference().sourceId(),
                transaction.currency(),
                transaction.totalDebits(),
                transaction.totalCredits(),
                transaction.entries().stream().map(entry -> new LedgerPostingEntryEvidence(
                        entry.account().accountId().value(),
                        entry.account().accountCode().name(),
                        entry.direction().name(),
                        entry.amount().amount(),
                        entry.amount().currency()
                )).toList(),
                transaction.compensatesTransactionId().map(LedgerTransactionId::value)
        );
    }
}
