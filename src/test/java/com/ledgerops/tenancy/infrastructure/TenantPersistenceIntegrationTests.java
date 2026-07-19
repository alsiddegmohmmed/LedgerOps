package com.ledgerops.tenancy.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.application.DuplicateTenantNameException;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantPersistenceIntegrationTests {

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void savesAndLoadsTenant() {
        Tenant tenant = new Tenant(
                TenantId.newId(),
                "Acme Payments",
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                TenantStatus.PENDING_ACTIVATION
        );

        tenantRepository.save(tenant);

        Optional<Tenant> loadedTenant =
                tenantRepository.findById(tenant.id());

        assertTrue(loadedTenant.isPresent());

        Tenant persistedTenant = loadedTenant.orElseThrow();

        assertEquals(tenant.id(), persistedTenant.id());
        assertEquals("Acme Payments", persistedTenant.name());
        assertEquals(
                Currency.getInstance("SAR"),
                persistedTenant.defaultCurrency()
        );
        assertEquals(
                Locale.forLanguageTag("en-SA"),
                persistedTenant.defaultLocale()
        );
        assertEquals(
                TenantStatus.PENDING_ACTIVATION,
                persistedTenant.status()
        );
    }

    @Test
    void updatesExistingTenantStatus() {
        Tenant tenant = new Tenant(
                TenantId.newId(),
                "Status Update Payments",
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("ar-SA"),
                TenantStatus.PENDING_ACTIVATION
        );

        tenantRepository.save(tenant);

        Tenant activatedTenant = tenant.activate();

        tenantRepository.save(activatedTenant);

        Tenant loadedTenant = tenantRepository.findById(tenant.id())
                .orElseThrow();

        assertEquals(tenant.id(), loadedTenant.id());
        assertEquals("Status Update Payments", loadedTenant.name());
        assertEquals(TenantStatus.ACTIVE, loadedTenant.status());
    }

    @Test
    void translatesDatabaseNameConstraintToTypedFailure() {
        Tenant firstTenant = tenantNamed("Database Constraint Payments");
        Tenant duplicateTenant = tenantNamed("Database Constraint Payments");

        tenantRepository.save(firstTenant);

        assertThrows(
                DuplicateTenantNameException.class,
                () -> tenantRepository.save(duplicateTenant)
        );
    }

    private Tenant tenantNamed(String name) {
        return new Tenant(
                TenantId.newId(),
                name,
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                TenantStatus.PENDING_ACTIVATION
        );
    }
}
