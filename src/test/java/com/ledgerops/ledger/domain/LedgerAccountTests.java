package com.ledgerops.ledger.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

class LedgerAccountTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant CREATED_AT = Instant.parse("2026-07-20T20:00:00Z");

    @Test
    void createsEveryAccountActiveWithItsImmutableIdentity() {
        LedgerAccountId accountId = LedgerAccountId.newId();
        UUID tenantId = UUID.randomUUID();

        LedgerAccount account = LedgerAccount.create(
                accountId,
                tenantId,
                AccountCode.CUSTOMER_RECEIVABLE,
                SAR,
                CREATED_AT
        );

        assertEquals(accountId, account.accountId());
        assertEquals(tenantId, account.tenantId());
        assertEquals(AccountCode.CUSTOMER_RECEIVABLE, account.accountCode());
        assertEquals(SAR, account.currency());
        assertEquals(LedgerAccountStatus.ACTIVE, account.status());
        assertEquals(CREATED_AT, account.createdAt());
    }

    @Test
    void exposesExactlyTheApprovedReleaseZeroOneAccountCodes() {
        List<AccountCode> approvedCodes = List.of(
                AccountCode.CUSTOMER_RECEIVABLE,
                AccountCode.MERCHANT_PAYABLE,
                AccountCode.PROVIDER_CLEARING,
                AccountCode.PLATFORM_FEE_REVENUE,
                AccountCode.REVERSAL_PAYABLE,
                AccountCode.SETTLEMENT_RECEIVABLE
        );

        assertEquals(approvedCodes, List.of(AccountCode.values()));
        for (AccountCode accountCode : approvedCodes) {
            assertEquals(accountCode, AccountCode.from(accountCode.name()));
            assertEquals(accountCode.name(), accountCode.value());
        }
    }

    @Test
    void exposesOnlyActiveAsAReleaseZeroOneStatus() {
        assertEquals(
                List.of(LedgerAccountStatus.ACTIVE),
                List.of(LedgerAccountStatus.values())
        );
    }

    @Test
    void rejectsUnsupportedAccountCodes() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AccountCode.from("INACTIVE_RECEIVABLE")
        );

        assertEquals(
                "Unsupported Release 0.1 account code: INACTIVE_RECEIVABLE",
                exception.getMessage()
        );
        assertThrows(IllegalArgumentException.class, () -> AccountCode.from("  "));
    }

    @Test
    void requiresEveryAccountField() {
        LedgerAccountId accountId = LedgerAccountId.newId();
        UUID tenantId = UUID.randomUUID();

        assertThrows(
                NullPointerException.class,
                () -> LedgerAccount.create(
                        null,
                        tenantId,
                        AccountCode.CUSTOMER_RECEIVABLE,
                        SAR,
                        CREATED_AT
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> LedgerAccount.create(
                        accountId,
                        null,
                        AccountCode.CUSTOMER_RECEIVABLE,
                        SAR,
                        CREATED_AT
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> LedgerAccount.create(accountId, tenantId, null, SAR, CREATED_AT)
        );
        assertThrows(
                NullPointerException.class,
                () -> LedgerAccount.create(
                        accountId,
                        tenantId,
                        AccountCode.CUSTOMER_RECEIVABLE,
                        null,
                        CREATED_AT
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> LedgerAccount.create(
                        accountId,
                        tenantId,
                        AccountCode.CUSTOMER_RECEIVABLE,
                        SAR,
                        null
                )
        );
    }
}
