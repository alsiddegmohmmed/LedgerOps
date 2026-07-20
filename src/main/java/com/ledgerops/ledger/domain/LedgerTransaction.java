package com.ledgerops.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LedgerTransaction {

    private final LedgerTransactionId id;
    private final UUID tenantId;
    private final LedgerSourceReference sourceReference;
    private final LedgerTransactionId compensatesTransactionId;
    private final Instant postedAt;
    private final Currency currency;
    private final List<LedgerEntry> entries;

    private LedgerTransaction(
            LedgerTransactionId id,
            UUID tenantId,
            LedgerSourceReference sourceReference,
            LedgerTransactionId compensatesTransactionId,
            Instant postedAt,
            List<LedgerEntry> entries
    ) {
        this.id = Objects.requireNonNull(id, "Ledger transaction ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        this.sourceReference = requireSourceTenant(sourceReference, tenantId);
        this.compensatesTransactionId = requireDifferentCompensation(
                compensatesTransactionId,
                id
        );
        this.postedAt = Objects.requireNonNull(postedAt, "Posted time must not be null");
        this.entries = List.copyOf(
                Objects.requireNonNull(entries, "Ledger entries must not be null")
        );
        this.currency = validateEntries(this.entries, tenantId);
    }

    public static LedgerTransaction post(
            LedgerTransactionId id,
            UUID tenantId,
            LedgerSourceReference sourceReference,
            Instant postedAt,
            List<LedgerEntry> entries
    ) {
        return new LedgerTransaction(
                id,
                tenantId,
                sourceReference,
                null,
                postedAt,
                entries
        );
    }

    public static LedgerTransaction postCompensation(
            LedgerTransactionId id,
            UUID tenantId,
            LedgerSourceReference sourceReference,
            LedgerTransactionId compensatesTransactionId,
            Instant postedAt,
            List<LedgerEntry> entries
    ) {
        return new LedgerTransaction(
                id,
                tenantId,
                sourceReference,
                Objects.requireNonNull(
                        compensatesTransactionId,
                        "Compensated Ledger transaction ID must not be null"
                ),
                postedAt,
                entries
        );
    }

    public LedgerTransactionId id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public LedgerSourceReference sourceReference() {
        return sourceReference;
    }

    public Optional<LedgerTransactionId> compensatesTransactionId() {
        return Optional.ofNullable(compensatesTransactionId);
    }

    public Instant postedAt() {
        return postedAt;
    }

    public Currency currency() {
        return currency;
    }

    public List<LedgerEntry> entries() {
        return entries;
    }

    public BigDecimal totalDebits() {
        return total(LedgerEntryDirection.DEBIT);
    }

    public BigDecimal totalCredits() {
        return total(LedgerEntryDirection.CREDIT);
    }

    public boolean isCompensating() {
        return compensatesTransactionId != null;
    }

    private BigDecimal total(LedgerEntryDirection direction) {
        return entries.stream()
                .filter(entry -> entry.direction() == direction)
                .map(entry -> entry.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(currency.getDefaultFractionDigits());
    }

    private static LedgerSourceReference requireSourceTenant(
            LedgerSourceReference sourceReference,
            UUID tenantId
    ) {
        Objects.requireNonNull(sourceReference, "Ledger source reference must not be null");
        if (!sourceReference.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Ledger source must belong to the transaction tenant"
            );
        }
        return sourceReference;
    }

    private static LedgerTransactionId requireDifferentCompensation(
            LedgerTransactionId compensatesTransactionId,
            LedgerTransactionId transactionId
    ) {
        if (transactionId.equals(compensatesTransactionId)) {
            throw new IllegalArgumentException(
                    "Ledger transaction cannot compensate itself"
            );
        }
        return compensatesTransactionId;
    }

    private static Currency validateEntries(List<LedgerEntry> entries, UUID tenantId) {
        if (entries.size() < 2) {
            throw new IllegalArgumentException(
                    "Posted Ledger transaction must contain at least two entries"
            );
        }

        Currency currency = entries.getFirst().amount().currency();
        boolean hasDebit = false;
        boolean hasCredit = false;
        BigDecimal debitTotal = BigDecimal.ZERO;
        BigDecimal creditTotal = BigDecimal.ZERO;

        for (LedgerEntry entry : entries) {
            if (!entry.account().tenantId().equals(tenantId)) {
                throw new IllegalArgumentException(
                        "Every Ledger entry account must belong to the transaction tenant"
                );
            }
            if (!entry.amount().currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "Mixed-currency Ledger transactions are prohibited"
                );
            }

            if (entry.direction() == LedgerEntryDirection.DEBIT) {
                hasDebit = true;
                debitTotal = debitTotal.add(entry.amount().amount());
            } else {
                hasCredit = true;
                creditTotal = creditTotal.add(entry.amount().amount());
            }
        }

        if (!hasDebit || !hasCredit) {
            throw new IllegalArgumentException(
                    "Posted Ledger transaction must contain at least one debit and one credit"
            );
        }
        if (debitTotal.compareTo(creditTotal) != 0) {
            throw new IllegalArgumentException(
                    "Posted Ledger transaction must balance debits and credits"
            );
        }

        return currency;
    }
}
