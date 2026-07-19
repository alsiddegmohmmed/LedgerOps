package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.customer.domain.Customer;
import com.ledgerops.customer.domain.CustomerReference;
import com.ledgerops.customer.domain.CustomerRepository;
import com.ledgerops.customer.domain.CustomerStatus;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.merchant.domain.Merchant;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.merchant.domain.MerchantStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentCreationIntegrationTests {

    @Autowired
    private PaymentCreationService paymentCreationService;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void equivalentSequentialRequestsReturnOriginalLogicalPayment() {
        Fixture fixture = activeFixture();
        CreatePaymentCommand command = command(fixture, "sequential-replay", "125.00");

        PaymentCreationResult first = paymentCreationService.createPayment(command);
        PaymentCreationResult replay = paymentCreationService.createPayment(command);

        assertTrue(first.created());
        assertFalse(replay.created());
        assertEquals(first.payment().id(), replay.payment().id());
        assertEquals(first.payment().status(), replay.payment().status());
        assertEquals(1, paymentCount(fixture.tenantId(), "sequential-replay"));
    }

    @Test
    void materiallyDifferentContentReturnsExplicitConflict() {
        Fixture fixture = activeFixture();
        paymentCreationService.createPayment(command(fixture, "content-conflict", "125.00"));

        PaymentIdempotencyConflictException exception = assertThrows(
                PaymentIdempotencyConflictException.class,
                () -> paymentCreationService.createPayment(
                        command(fixture, "content-conflict", "126.00")
                )
        );

        assertEquals(fixture.tenantId(), exception.tenantId());
        assertEquals(1, paymentCount(fixture.tenantId(), "content-conflict"));
    }

    @Test
    void differentMerchantUnderSameTenantAndKeyIsConflictingContent() {
        Fixture first = activeFixture();
        Merchant secondMerchant = merchant(
                first.tenantId(),
                MerchantId.newId(),
                "Second Merchant"
        );
        merchantRepository.save(secondMerchant);

        paymentCreationService.createPayment(command(first, "merchant-conflict", "50.00"));

        CreatePaymentCommand changedMerchant = new CreatePaymentCommand(
                first.tenantId(),
                secondMerchant.id().value(),
                first.customerId(),
                new BigDecimal("50.00"),
                "SAR",
                "card",
                "merchant-conflict"
        );

        assertThrows(
                PaymentIdempotencyConflictException.class,
                () -> paymentCreationService.createPayment(changedMerchant)
        );
        assertEquals(1, paymentCount(first.tenantId(), "merchant-conflict"));
    }

    @Test
    void sameIdempotencyKeyIsIndependentAcrossTenants() {
        Fixture first = activeFixture();
        Fixture second = activeFixture();

        PaymentCreationResult firstResult = paymentCreationService.createPayment(
                command(first, "shared-across-tenants", "10.00")
        );
        PaymentCreationResult secondResult = paymentCreationService.createPayment(
                command(second, "shared-across-tenants", "10.00")
        );

        assertTrue(firstResult.created());
        assertTrue(secondResult.created());
        assertFalse(firstResult.payment().id().equals(secondResult.payment().id()));
    }

    @Test
    void coordinatedConcurrentRequestsCreateOneFinalPayment() throws Exception {
        Fixture fixture = activeFixture();
        CreatePaymentCommand command = command(fixture, "concurrent-replay", "75.00");
        int competitors = 8;
        CyclicBarrier barrier = new CyclicBarrier(competitors);
        ExecutorService executor = Executors.newFixedThreadPool(competitors);

        try {
            List<Future<PaymentCreationResult>> futures = new ArrayList<>();
            for (int index = 0; index < competitors; index++) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return paymentCreationService.createPayment(command);
                }));
            }

            List<PaymentCreationResult> results = new ArrayList<>();
            for (Future<PaymentCreationResult> future : futures) {
                results.add(future.get());
            }

            Set<?> paymentIds = results.stream()
                    .map(result -> result.payment().id())
                    .collect(java.util.stream.Collectors.toSet());
            long createdResults = results.stream()
                    .filter(PaymentCreationResult::created)
                    .count();

            assertEquals(1, paymentIds.size());
            assertEquals(1, createdResults);
            assertEquals(1, paymentCount(fixture.tenantId(), "concurrent-replay"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void suspendedTenantCannotCreateNewPaymentButCanReplayExistingResult() {
        Fixture fixture = activeFixture();
        CreatePaymentCommand existing = command(fixture, "before-suspension", "20.00");
        PaymentCreationResult created = paymentCreationService.createPayment(existing);
        Tenant activeTenant = tenantRepository.findById(TenantId.from(fixture.tenantId()))
                .orElseThrow();
        tenantRepository.save(activeTenant.suspend());

        PaymentCreationResult replay = paymentCreationService.createPayment(existing);

        assertFalse(replay.created());
        assertEquals(created.payment().id(), replay.payment().id());

        PaymentReferenceUnavailableException exception = assertThrows(
                PaymentReferenceUnavailableException.class,
                () -> paymentCreationService.createPayment(
                        command(fixture, "after-suspension", "20.00")
                )
        );
        assertEquals(PaymentReferenceType.TENANT, exception.referenceType());
        assertEquals("INACTIVE", exception.reason());
        assertEquals(0, paymentCount(fixture.tenantId(), "after-suspension"));
    }

    @Test
    void inactiveOrWronglyScopedMerchantAndCustomerCannotCreatePayment() {
        Fixture fixture = activeFixture();
        Merchant suspendedMerchant = new Merchant(
                MerchantId.newId(),
                TenantReference.from(fixture.tenantId()),
                "Suspended Merchant",
                MerchantStatus.SUSPENDED
        );
        merchantRepository.save(suspendedMerchant);

        PaymentReferenceUnavailableException merchantFailure = assertThrows(
                PaymentReferenceUnavailableException.class,
                () -> paymentCreationService.createPayment(new CreatePaymentCommand(
                        fixture.tenantId(),
                        suspendedMerchant.id().value(),
                        fixture.customerId(),
                        new BigDecimal("30.00"),
                        "SAR",
                        "card",
                        "suspended-merchant"
                ))
        );
        PaymentReferenceUnavailableException customerFailure = assertThrows(
                PaymentReferenceUnavailableException.class,
                () -> paymentCreationService.createPayment(new CreatePaymentCommand(
                        fixture.tenantId(),
                        fixture.merchantId(),
                        UUID.randomUUID(),
                        new BigDecimal("30.00"),
                        "SAR",
                        "card",
                        "unknown-customer"
                ))
        );

        assertEquals(PaymentReferenceType.MERCHANT, merchantFailure.referenceType());
        assertEquals(PaymentReferenceType.CUSTOMER, customerFailure.referenceType());
    }

    private Fixture activeFixture() {
        Tenant tenant = new Tenant(
                TenantId.newId(),
                "Payment Tenant " + UUID.randomUUID(),
                Currency.getInstance("SAR"),
                Locale.forLanguageTag("en-SA"),
                TenantStatus.ACTIVE
        );
        tenantRepository.save(tenant);

        Merchant merchant = merchant(
                tenant.id().value(),
                MerchantId.newId(),
                "Payment Merchant"
        );
        merchantRepository.save(merchant);

        com.ledgerops.customer.domain.CustomerId customerId =
                com.ledgerops.customer.domain.CustomerId.newId();
        Customer customer = new Customer(
                customerId,
                MerchantReference.from(tenant.id().value(), merchant.id().value()),
                CustomerReference.from("payment-customer-" + UUID.randomUUID()),
                CustomerStatus.ACTIVE
        );
        customerRepository.save(customer);

        return new Fixture(
                tenant.id().value(),
                merchant.id().value(),
                customerId.value()
        );
    }

    private Merchant merchant(UUID tenantId, MerchantId merchantId, String name) {
        return new Merchant(
                merchantId,
                TenantReference.from(tenantId),
                name,
                MerchantStatus.ACTIVE
        );
    }

    private CreatePaymentCommand command(
            Fixture fixture,
            String idempotencyKey,
            String amount
    ) {
        return new CreatePaymentCommand(
                fixture.tenantId(),
                fixture.merchantId(),
                fixture.customerId(),
                new BigDecimal(amount),
                "SAR",
                "card",
                idempotencyKey
        );
    }

    private int paymentCount(UUID tenantId, String idempotencyKey) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                  FROM payment.payments
                 WHERE tenant_id = ?
                   AND idempotency_key = ?
                """,
                Integer.class,
                tenantId,
                idempotencyKey
        );
        return count == null ? 0 : count;
    }

    private record Fixture(
            UUID tenantId,
            UUID merchantId,
            UUID customerId
    ) {
    }
}
