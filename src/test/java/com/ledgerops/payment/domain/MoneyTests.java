package com.ledgerops.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

class MoneyTests {

    @Test
    void storesBigDecimalWithExplicitCurrencyPrecision() {
        Money money = Money.of(
                new BigDecimal("125.5"),
                Currency.getInstance("SAR")
        );

        assertEquals(new BigDecimal("125.50"), money.amount());
        assertEquals(Currency.getInstance("SAR"), money.currency());
        assertTrue(money.isPositive());
    }

    @Test
    void supportsCurrenciesWithDifferentFractionDigits() {
        Money yen = Money.of(
                new BigDecimal("125"),
                Currency.getInstance("JPY")
        );
        Money dinar = Money.of(
                new BigDecimal("12.345"),
                Currency.getInstance("KWD")
        );

        assertEquals(new BigDecimal("125"), yen.amount());
        assertEquals(new BigDecimal("12.345"), dinar.amount());
    }

    @Test
    void rejectsNegativeAmounts() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sar("-0.01")
        );

        assertEquals("Money amount must not be negative", exception.getMessage());
    }

    @Test
    void rejectsAmountExceedingCurrencyPrecision() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sar("10.001")
        );

        assertEquals(
                "Money amount exceeds currency precision for SAR",
                exception.getMessage()
        );
    }

    @Test
    void permitsZeroButDoesNotTreatItAsPositivePaymentValue() {
        Money zero = sar("0");

        assertEquals(new BigDecimal("0.00"), zero.amount());
        assertFalse(zero.isPositive());
    }

    @Test
    void addsAndSubtractsOnlyWithinSameCurrency() {
        assertEquals(
                sar("15.50"),
                sar("10.25").add(sar("5.25"))
        );
        assertEquals(
                sar("5.00"),
                sar("10.25").subtract(sar("5.25"))
        );
    }

    @Test
    void prohibitsArithmeticAcrossCurrencies() {
        Money sar = sar("10.00");
        Money usd = Money.of(
                new BigDecimal("1.00"),
                Currency.getInstance("USD")
        );

        IllegalArgumentException addition = assertThrows(
                IllegalArgumentException.class,
                () -> sar.add(usd)
        );
        IllegalArgumentException subtraction = assertThrows(
                IllegalArgumentException.class,
                () -> sar.subtract(usd)
        );

        assertEquals("Currency mismatch: SAR and USD", addition.getMessage());
        assertEquals("Currency mismatch: SAR and USD", subtraction.getMessage());
    }

    @Test
    void prohibitsSubtractionThatWouldCreateNegativeMoney() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> sar("5.00").subtract(sar("5.01"))
        );

        assertEquals(
                "Money subtraction must not produce a negative amount",
                exception.getMessage()
        );
    }

    private Money sar(String amount) {
        return Money.of(
                new BigDecimal(amount),
                Currency.getInstance("SAR")
        );
    }
}
