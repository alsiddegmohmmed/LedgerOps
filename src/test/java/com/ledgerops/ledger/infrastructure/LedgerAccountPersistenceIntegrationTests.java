package com.ledgerops.ledger.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.ledger.domain.AccountCode;
import com.ledgerops.ledger.domain.LedgerAccount;
import com.ledgerops.ledger.domain.LedgerAccountId;
import com.ledgerops.ledger.domain.LedgerAccountRepository;
import com.ledgerops.ledger.domain.LedgerAccountStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerAccountPersistenceIntegrationTests {

    private static final Currency SAR = Currency.getInstance("SAR");
    private static final Instant CREATED_AT = Instant.parse("2026-07-20T10:15:30Z");

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Test
    void insertsAndLoadsTheImmutableAccountByTenantScopedIdentifiers() {
        LedgerAccount account = account(
                UUID.randomUUID(),
                AccountCode.CUSTOMER_RECEIVABLE,
                SAR
        );

        accountRepository.insert(account);

        LedgerAccount byId = accountRepository.findById(
                account.tenantId(),
                account.accountId()
        ).orElseThrow();
        LedgerAccount byIdentity = accountRepository.findByIdentity(
                account.tenantId(),
                account.accountCode(),
                account.currency()
        ).orElseThrow();

        assertAccountEquals(account, byId);
        assertAccountEquals(account, byIdentity);
        assertEquals(LedgerAccountStatus.ACTIVE, byId.status());
    }

    @Test
    void tenantScopedLookupsDoNotDiscloseAnotherTenantsAccount() {
        LedgerAccount account = account(
                UUID.randomUUID(),
                AccountCode.MERCHANT_PAYABLE,
                SAR
        );
        accountRepository.insert(account);

        UUID otherTenant = UUID.randomUUID();

        assertTrue(accountRepository.findById(
                account.tenantId(),
                account.accountId()
        ).isPresent());
        assertFalse(accountRepository.findById(
                otherTenant,
                account.accountId()
        ).isPresent());
        assertFalse(accountRepository.findByIdentity(
                otherTenant,
                account.accountCode(),
                account.currency()
        ).isPresent());
    }

    @Test
    void duplicateTenantCodeAndCurrencyIsRejectedByPostgres() {
        UUID tenantId = UUID.randomUUID();
        accountRepository.insert(account(
                tenantId,
                AccountCode.PROVIDER_CLEARING,
                SAR
        ));

        LedgerAccount duplicate = account(
                tenantId,
                AccountCode.PROVIDER_CLEARING,
                SAR
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> accountRepository.insert(duplicate)
        );
    }

    @Test
    void sameIdentityComponentsRemainIndependentAcrossTenantAndCurrency() {
        UUID firstTenant = UUID.randomUUID();
        UUID secondTenant = UUID.randomUUID();
        Currency usd = Currency.getInstance("USD");

        accountRepository.insert(account(
                firstTenant,
                AccountCode.SETTLEMENT_RECEIVABLE,
                SAR
        ));
        accountRepository.insert(account(
                secondTenant,
                AccountCode.SETTLEMENT_RECEIVABLE,
                SAR
        ));
        accountRepository.insert(account(
                firstTenant,
                AccountCode.SETTLEMENT_RECEIVABLE,
                usd
        ));

        assertTrue(accountRepository.findByIdentity(
                firstTenant,
                AccountCode.SETTLEMENT_RECEIVABLE,
                SAR
        ).isPresent());
        assertTrue(accountRepository.findByIdentity(
                secondTenant,
                AccountCode.SETTLEMENT_RECEIVABLE,
                SAR
        ).isPresent());
        assertTrue(accountRepository.findByIdentity(
                firstTenant,
                AccountCode.SETTLEMENT_RECEIVABLE,
                usd
        ).isPresent());
    }

    private LedgerAccount account(
            UUID tenantId,
            AccountCode accountCode,
            Currency currency
    ) {
        return LedgerAccount.create(
                LedgerAccountId.newId(),
                tenantId,
                accountCode,
                currency,
                CREATED_AT
        );
    }

    private void assertAccountEquals(
            LedgerAccount expected,
            LedgerAccount actual
    ) {
        assertEquals(expected.accountId(), actual.accountId());
        assertEquals(expected.tenantId(), actual.tenantId());
        assertEquals(expected.accountCode(), actual.accountCode());
        assertEquals(expected.currency(), actual.currency());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.createdAt(), actual.createdAt());
    }
}
