package com.ledgerops.merchant.application;

import com.ledgerops.merchant.api.MerchantLifecyclePort;
import com.ledgerops.merchant.api.MerchantLifecycleRequest;
import com.ledgerops.merchant.api.MerchantReference;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MerchantLifecycleIntegrationTests {

    @Autowired
    private MerchantLifecyclePort lifecycle;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void suspendsAndReactivatesMerchantWithAuditAndVersionedOutboxEvents() {
        MerchantReference merchant = createMerchant();
        UUID suspendCorrelation = UUID.randomUUID();
        UUID suspendOperation = UUID.randomUUID();

        lifecycle.suspend(new MerchantLifecycleRequest(
                merchant,
                "https://issuer.example",
                "platform-admin-" + merchant.value(),
                suspendCorrelation,
                suspendOperation
        ));

        assertThat(statusOf(merchant)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForMap(
                "SELECT action_type, target_type, target_id, details "
                        + "FROM audit.audit_records WHERE correlation_id = ?",
                suspendCorrelation.toString()
        )).satisfies(row -> {
            assertThat(row.get("action_type")).isEqualTo("merchant.suspended");
            assertThat(row.get("target_type")).isEqualTo("merchant");
            assertThat(row.get("target_id")).isEqualTo(merchant.value().toString());
            assertThat(row.get("details").toString())
                    .contains("\"previousStatus\":\"ACTIVE\"")
                    .contains("\"status\":\"SUSPENDED\"");
        });
        assertThat(outboxFor(merchant, "merchant-event:" + merchant.value() + ":1"))
                .satisfies(row -> {
                    assertThat(row.get("causation_id")).isEqualTo(suspendOperation);
                    assertThat(row.get("payload").toString())
                            .contains("\"version\":1")
                            .contains("\"status\":\"SUSPENDED\"");
                });

        UUID activateCorrelation = UUID.randomUUID();
        UUID activateOperation = UUID.randomUUID();
        lifecycle.activate(new MerchantLifecycleRequest(
                merchant,
                "https://issuer.example",
                "platform-admin-" + merchant.value(),
                activateCorrelation,
                activateOperation
        ));

        assertThat(statusOf(merchant)).isEqualTo("ACTIVE");
        assertThat(outboxFor(merchant, "merchant-event:" + merchant.value() + ":2"))
                .satisfies(row -> {
                    assertThat(row.get("causation_id")).isEqualTo(activateOperation);
                    assertThat(row.get("payload").toString())
                            .contains("\"version\":2")
                            .contains("\"status\":\"ACTIVE\"");
                });
    }

    @Test
    void waitsForExistingMerchantRowLockBeforeChangingLifecycleState() throws Exception {
        MerchantReference merchant = createMerchant();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForObject(
                        "SELECT id FROM merchant.merchants WHERE id = ? FOR UPDATE",
                        UUID.class,
                        merchant.value()
                );
                lockHeld.countDown();
                await(releaseLock);
            }));
            lockHeld.await(5, TimeUnit.SECONDS);

            Future<MerchantReference> transition = executor.submit(() -> lifecycle.suspend(
                    new MerchantLifecycleRequest(
                            merchant,
                            "https://issuer.example",
                            "platform-admin-" + merchant.value(),
                            UUID.randomUUID(),
                            UUID.randomUUID()
                    )
            ));

            assertThatThrownBy(() -> transition.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseLock.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertThat(transition.get(5, TimeUnit.SECONDS)).isEqualTo(merchant);
            assertThat(statusOf(merchant)).isEqualTo("SUSPENDED");
        } finally {
            releaseLock.countDown();
        }
    }

    private MerchantReference createMerchant() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO tenancy.tenants (
                    id, name, default_currency, default_locale, status,
                    version, created_at, updated_at
                ) VALUES (?, ?, 'SAR', 'en-SA', 'ACTIVE', 0, ?, ?)
                """,
                tenantId,
                "Merchant Lifecycle Tenant " + tenantId,
                Timestamp.from(now),
                Timestamp.from(now)
        );
        TenantReference tenant = TenantReference.from(tenantId);
        Merchant saved = merchants.save(new Merchant(
                MerchantId.newId(),
                tenant,
                "Merchant Lifecycle " + UUID.randomUUID(),
                MerchantStatus.ACTIVE
        ));
        return MerchantReference.from(tenantId, saved.id().value());
    }

    private String statusOf(MerchantReference merchant) {
        return jdbc.queryForObject(
                "SELECT status FROM merchant.merchants WHERE id = ? AND tenant_id = ?",
                String.class,
                merchant.value(),
                merchant.tenantId()
        );
    }

    private Map<String, Object> outboxFor(
            MerchantReference merchant,
            String deduplicationKey
    ) {
        return jdbc.queryForMap(
                "SELECT causation_id, payload FROM messaging.outbox "
                        + "WHERE producer_name = 'merchant' AND aggregate_id = ? "
                        + "AND deduplication_key = ?",
                merchant.value(),
                deduplicationKey
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding Merchant row lock", exception);
        }
    }
}
