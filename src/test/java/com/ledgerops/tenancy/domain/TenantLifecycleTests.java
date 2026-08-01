package com.ledgerops.tenancy.domain;

import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantLifecycleTests {
    private static final TenantActivationPrerequisites SATISFIED =
            new TenantActivationPrerequisites(true, true, true);

    @Test
    void activatesPendingTenantWithExplicitSatisfiedFacts() {
        Tenant activeTenant = tenantWithStatus(TenantStatus.PENDING_ACTIVATION)
                .activate(SATISFIED);

        assertEquals(TenantStatus.ACTIVE, activeTenant.status());
        assertTrue(activeTenant.canCreateOperationalActivity());
    }

    @Test
    void reactivatesSuspendedTenantWithExplicitSatisfiedFacts() {
        Tenant activeTenant = tenantWithStatus(TenantStatus.SUSPENDED)
                .activate(SATISFIED);

        assertEquals(TenantStatus.ACTIVE, activeTenant.status());
    }

    @Test
    void preservesLegacyActivationTransitionUntilOrchestrationSuppliesPrerequisites() {
        for (TenantStatus status : SetLike.activationSourceStatuses()) {
            assertEquals(TenantStatus.ACTIVE, tenantWithStatus(status).activate().status());
        }
    }

    @Test
    void eachPrerequisiteIsRequiredForActivationAndReactivation() {
        for (TenantStatus status : SetLike.activationSourceStatuses()) {
            assertPrerequisitesRejected(status,
                    new TenantActivationPrerequisites(false, true, true));
            assertPrerequisitesRejected(status,
                    new TenantActivationPrerequisites(true, false, true));
            assertPrerequisitesRejected(status,
                    new TenantActivationPrerequisites(true, true, false));
            assertPrerequisitesRejected(status, null);
        }
    }

    @Test
    void suspendsActiveTenantAndGatesNewOperationalActivity() {
        Tenant suspendedTenant = tenantWithStatus(TenantStatus.ACTIVE).suspend();

        assertEquals(TenantStatus.SUSPENDED, suspendedTenant.status());
        assertFalse(suspendedTenant.canCreateOperationalActivity());
    }

    @Test
    void rejectsInvalidLifecycleTransitions() {
        assertThrows(IllegalStateException.class,
                () -> tenantWithStatus(TenantStatus.PENDING_ACTIVATION).suspend());
        assertThrows(IllegalStateException.class,
                () -> tenantWithStatus(TenantStatus.ACTIVE).activate(SATISFIED));
        assertThrows(IllegalStateException.class,
                () -> tenantWithStatus(TenantStatus.ARCHIVED).activate(SATISFIED));
        assertThrows(IllegalStateException.class,
                () -> tenantWithStatus(TenantStatus.ARCHIVED).archive());
    }

    private void assertPrerequisitesRejected(
            TenantStatus status,
            TenantActivationPrerequisites prerequisites
    ) {
        assertThrows(IllegalStateException.class,
                () -> tenantWithStatus(status).activate(prerequisites));
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

    private static final class SetLike {
        private SetLike() { }

        private static Iterable<TenantStatus> activationSourceStatuses() {
            return Stream.of(TenantStatus.PENDING_ACTIVATION, TenantStatus.SUSPENDED).toList();
        }
    }
}
