package com.ledgerops.tenancy.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Currency;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class TenantLifecycleTests {

    @Test
    void activatesPendingTenant() {
        Tenant pendingTenant = tenantWithStatus(TenantStatus.PENDING_ACTIVATION);

        Tenant activeTenant = pendingTenant.activate();

        assertEquals(TenantStatus.ACTIVE, activeTenant.status());
        assertTrue(activeTenant.canCreateOperationalActivity());
    }

    @Test
    void suspendsActiveTenant() {
        Tenant activeTenant = tenantWithStatus(TenantStatus.ACTIVE);

        Tenant suspendedTenant = activeTenant.suspend();

        assertEquals(TenantStatus.SUSPENDED, suspendedTenant.status());
        assertFalse(suspendedTenant.canCreateOperationalActivity());
    }

    @Test
    void reactivatesSuspendedTenant() {
        Tenant suspendedTenant = tenantWithStatus(TenantStatus.SUSPENDED);

        Tenant activeTenant = suspendedTenant.activate();

        assertEquals(TenantStatus.ACTIVE, activeTenant.status());
    }

    @Test
    void rejectsSuspendingPendingTenant() {
        Tenant pendingTenant = tenantWithStatus(TenantStatus.PENDING_ACTIVATION);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                pendingTenant::suspend
        );

        assertEquals(
                "Tenant cannot transition from PENDING_ACTIVATION to SUSPENDED",
                exception.getMessage()
        );
    }

    @Test
    void archivedTenantCannotBeReactivated() {
        Tenant archivedTenant = tenantWithStatus(TenantStatus.ARCHIVED);

        assertThrows(
                IllegalStateException.class,
                archivedTenant::activate
        );
    }

    private Tenant tenantWithStatus(TenantStatus status) {
        return new Tenant(
                TenantId.newId(),
                "Acme Payments",
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                status
        );
    }
}
