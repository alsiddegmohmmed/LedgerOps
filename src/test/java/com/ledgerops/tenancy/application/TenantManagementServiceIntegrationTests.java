package com.ledgerops.tenancy.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.api.TenantActivityQuery;
import com.ledgerops.tenancy.api.TenantActivityStatus;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TenantManagementServiceIntegrationTests {

    @Autowired
    private TenantManagementService tenantManagementService;

    @Autowired
    private TenantActivityQuery tenantActivityQuery;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    void operationalActivityLockSerializesConcurrentSuspension() throws Exception {
        Tenant created = tenantManagementService.createTenant(command(
                "Activity lock " + java.util.UUID.randomUUID()
        ));
        Tenant active = tenantManagementService.activateTenant(created.id());
        CountDownLatch activityLockHeld = new CountDownLatch(1);
        CountDownLatch allowActivityCommit = new CountDownLatch(1);
        CountDownLatch suspensionStarted = new CountDownLatch(1);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<TenantActivityStatus> activity = executor.submit(() -> transactions.execute(
                    status -> {
                        TenantActivityStatus result = tenantActivityQuery.evaluateForUpdate(
                                TenantReference.from(active.id().value())
                        );
                        activityLockHeld.countDown();
                        await(allowActivityCommit);
                        return result;
                    }
            ));
            activityLockHeld.await();
            Future<Tenant> suspension = executor.submit(() -> {
                suspensionStarted.countDown();
                return tenantManagementService.suspendTenant(active.id());
            });
            suspensionStarted.await();

            assertThrows(
                    TimeoutException.class,
                    () -> suspension.get(250, TimeUnit.MILLISECONDS)
            );
            allowActivityCommit.countDown();

            assertEquals(TenantActivityStatus.ALLOWED, activity.get());
            assertEquals(TenantStatus.SUSPENDED, suspension.get().status());
        } finally {
            allowActivityCommit.countDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating tenant lock test", exception);
        }
    }

    private CreateTenantCommand command(String name) {
        return new CreateTenantCommand(
                name,
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA")
        );
    }
}
