package com.ledgerops.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

class LedgerAmountTests {

    @Test
    void storesPositiveAmountsUsingTheExplicitCurrencyPrecision() {
        LedgerAmount amount = LedgerAmount.of(
                new BigDecimal("125.5"),
                Currency.getInstance("SAR")
        );

        assertEquals(new BigDecimal("125.50"), amount.amount());
        assertEquals(Currency.getInstance("SAR"), amount.currency());
    }

    @Test
    void supportsCurrenciesWithDifferentFractionDigits() {
        LedgerAmount yen = LedgerAmount.of(
                new BigDecimal("125"),
                Currency.getInstance("JPY")
        );
        LedgerAmount dinar = LedgerAmount.of(
                new BigDecimal("12.345"),
                Currency.getInstance("KWD")
        );

        assertEquals(new BigDecimal("125"), yen.amount());
        assertEquals(new BigDecimal("12.345"), dinar.amount());
    }

    @Test
    void rejectsZeroAndNegativeEntryAmounts() {
        assertThrows(IllegalArgumentException.class, () -> sar("0"));
        assertThrows(IllegalArgumentException.class, () -> sar("-0.01"));
    }

    @Test
    void rejectsAmountsExceedingCurrencyPrecision() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sar("10.001")
        );

        assertEquals(
                "Ledger amount exceeds currency precision for SAR",
                exception.getMessage()
        );
    }

    @Test
    void rejectsBlankAccountCodes() {
        assertThrows(IllegalArgumentException.class, () -> AccountCode.from("  "));
    }

    private LedgerAmount sar(String value) {
        return LedgerAmount.of(
                new BigDecimal(value),
                Currency.getInstance("SAR")
        );
    }
}
