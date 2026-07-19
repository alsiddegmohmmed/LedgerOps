package com.ledgerops.tenancy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

class TenantManagementServiceTests {

    private final InMemoryTenantRepository repository =
            new InMemoryTenantRepository();
    private final TenantManagementService service =
            new TenantManagementService(repository);

    @Test
    void createsPendingTenantWithNormalizedName() {
        Tenant tenant = service.createTenant(command("  Acme Payments  "));

        assertEquals("Acme Payments", tenant.name());
        assertEquals(TenantStatus.PENDING_ACTIVATION, tenant.status());
        assertSame(tenant, repository.findById(tenant.id()).orElseThrow());
    }

    @Test
    void rejectsExistingTenantNameBeforeSaving() {
        service.createTenant(command("Acme Payments"));

        DuplicateTenantNameException exception = assertThrows(
                DuplicateTenantNameException.class,
                () -> service.createTenant(command("  Acme Payments  "))
        );

        assertEquals("Acme Payments", exception.tenantName());
        assertEquals(1, repository.size());
    }

    @Test
    void transitionsTenantThroughSupportedLifecycle() {
        Tenant created = service.createTenant(command("Lifecycle Payments"));

        Tenant active = service.activateTenant(created.id());
        Tenant suspended = service.suspendTenant(created.id());
        Tenant archived = service.archiveTenant(created.id());

        assertEquals(TenantStatus.ACTIVE, active.status());
        assertEquals(TenantStatus.SUSPENDED, suspended.status());
        assertFalse(suspended.canCreateOperationalActivity());
        assertEquals(TenantStatus.ARCHIVED, archived.status());
    }

    @Test
    void returnsTypedFailureWithoutChangingInvalidLifecycle() {
        Tenant pending = service.createTenant(command("Pending Payments"));

        TenantLifecycleException exception = assertThrows(
                TenantLifecycleException.class,
                () -> service.suspendTenant(pending.id())
        );

        assertEquals(pending.id(), exception.tenantId());
        assertEquals(TenantStatus.SUSPENDED, exception.targetStatus());
        assertEquals(
                TenantStatus.PENDING_ACTIVATION,
                service.getTenant(pending.id()).status()
        );
    }

    @Test
    void returnsTypedFailureForUnknownTenant() {
        TenantId tenantId = TenantId.newId();

        TenantNotFoundException exception = assertThrows(
                TenantNotFoundException.class,
                () -> service.getTenant(tenantId)
        );

        assertEquals(tenantId, exception.tenantId());
    }

    private CreateTenantCommand command(String name) {
        return new CreateTenantCommand(
                name,
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA")
        );
    }

    private static final class InMemoryTenantRepository
            implements TenantRepository {

        private final Map<TenantId, Tenant> tenants = new HashMap<>();

        @Override
        public Tenant save(Tenant tenant) {
            tenants.put(tenant.id(), tenant);
            return tenant;
        }

        @Override
        public Optional<Tenant> findById(TenantId tenantId) {
            return Optional.ofNullable(tenants.get(tenantId));
        }

        @Override
        public boolean existsByName(String name) {
            return tenants.values().stream()
                    .anyMatch(tenant -> tenant.name().equals(name));
        }

        int size() {
            return tenants.size();
        }
    }
}
