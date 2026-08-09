package com.ledgerops.merchant.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.merchant.domain.Merchant;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.merchant.domain.MerchantStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.api.TenantReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MerchantPersistenceIntegrationTests {

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void savesAndLoadsMerchantInsideOwningTenant() {
        TenantReference tenantReference = tenantReference();
        Merchant merchant = merchant(
                MerchantId.newId(),
                tenantReference,
                "Persistence Merchant",
                MerchantStatus.ACTIVE
        );

        merchantRepository.save(merchant);

        Merchant loaded = merchantRepository.findById(
                        tenantReference,
                        merchant.id()
                )
                .orElseThrow();

        assertEquals(merchant.id(), loaded.id());
        assertEquals(tenantReference, loaded.tenantReference());
        assertEquals("Persistence Merchant", loaded.name());
        assertEquals(MerchantStatus.ACTIVE, loaded.status());
    }

    @Test
    void tenantScopedLookupDoesNotDiscloseAnotherTenantsMerchant() {
        TenantReference owningTenant = tenantReference();
        TenantReference otherTenant = tenantReference();
        Merchant merchant = merchant(
                MerchantId.newId(),
                owningTenant,
                "Isolated Merchant",
                MerchantStatus.ACTIVE
        );

        merchantRepository.save(merchant);

        assertTrue(merchantRepository.findById(
                owningTenant,
                merchant.id()
        ).isPresent());
        assertFalse(merchantRepository.findById(
                otherTenant,
                merchant.id()
        ).isPresent());
    }

    @Test
    void merchantNameUniquenessIsScopedToTenant() {
        TenantReference firstTenant = tenantReference();
        TenantReference secondTenant = tenantReference();

        merchantRepository.save(merchant(
                MerchantId.newId(),
                firstTenant,
                "Shared Merchant Name",
                MerchantStatus.ACTIVE
        ));
        merchantRepository.save(merchant(
                MerchantId.newId(),
                secondTenant,
                "Shared Merchant Name",
                MerchantStatus.ACTIVE
        ));

        assertTrue(merchantRepository.existsByName(
                firstTenant,
                "Shared Merchant Name"
        ));
        assertTrue(merchantRepository.existsByName(
                secondTenant,
                "Shared Merchant Name"
        ));
    }

    @Test
    void duplicateMerchantNameInsideTenantIsRejectedByPostgres() {
        TenantReference tenantReference = tenantReference();
        merchantRepository.save(merchant(
                MerchantId.newId(),
                tenantReference,
                "Duplicate Merchant Name",
                MerchantStatus.ACTIVE
        ));

        Merchant duplicate = merchant(
                MerchantId.newId(),
                tenantReference,
                "Duplicate Merchant Name",
                MerchantStatus.ACTIVE
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> merchantRepository.save(duplicate)
        );
    }

    @Test
    void merchantIdentityCannotBeReassignedToAnotherTenant() {
        MerchantId merchantId = MerchantId.newId();
        merchantRepository.save(merchant(
                merchantId,
                tenantReference(),
                "Original Tenant Merchant",
                MerchantStatus.ACTIVE
        ));

        Merchant reassigned = merchant(
                merchantId,
                tenantReference(),
                "Other Tenant Merchant",
                MerchantStatus.SUSPENDED
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> merchantRepository.save(reassigned)
        );
    }

    private Merchant merchant(
            MerchantId merchantId,
            TenantReference tenantReference,
            String name,
            MerchantStatus status
    ) {
        return new Merchant(merchantId, tenantReference, name, status);
    }

    private TenantReference tenantReference() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', 'PENDING_ACTIVATION', 0, ?, ?)
                """,
                tenantId,
                "Merchant Persistence Tenant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return TenantReference.from(tenantId);
    }
}
