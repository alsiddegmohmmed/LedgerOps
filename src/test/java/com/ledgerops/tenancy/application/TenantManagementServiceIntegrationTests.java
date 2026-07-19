package com.ledgerops.tenancy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.Currency;
import java.util.Locale;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantManagementServiceIntegrationTests {

    @Autowired
    private TenantManagementService tenantManagementService;

    @Test
    void persistsLifecycleChangesAndKeepsSuspendedHistoryReadable() {
        Tenant created = tenantManagementService.createTenant(
                command("Application Lifecycle Payments")
        );

        tenantManagementService.activateTenant(created.id());
        Tenant suspended = tenantManagementService.suspendTenant(created.id());
        Tenant loaded = tenantManagementService.getTenant(created.id());

        assertEquals(TenantStatus.SUSPENDED, suspended.status());
        assertEquals(TenantStatus.SUSPENDED, loaded.status());
        assertFalse(loaded.canCreateOperationalActivity());
    }

    @Test
    void rejectsDuplicateTenantNameWithTypedFailure() {
        tenantManagementService.createTenant(command("Duplicate Application Payments"));

        DuplicateTenantNameException exception = assertThrows(
                DuplicateTenantNameException.class,
                () -> tenantManagementService.createTenant(
                        command("Duplicate Application Payments")
                )
        );

        assertEquals("Duplicate Application Payments", exception.tenantName());
    }

    @Test
    void rejectsInvalidTransitionWithoutChangingPersistedStatus() {
        Tenant pending = tenantManagementService.createTenant(
                command("Invalid Transition Payments")
        );

        TenantLifecycleException exception = assertThrows(
                TenantLifecycleException.class,
                () -> tenantManagementService.suspendTenant(pending.id())
        );

        assertEquals(TenantStatus.SUSPENDED, exception.targetStatus());
        assertEquals(
                TenantStatus.PENDING_ACTIVATION,
                tenantManagementService.getTenant(pending.id()).status()
        );
    }

    private CreateTenantCommand command(String name) {
        return new CreateTenantCommand(
                name,
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA")
        );
    }
}
