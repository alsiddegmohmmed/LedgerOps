package com.ledgerops.payment.infrastructure;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.application.PaymentLifecycleStore;
import com.ledgerops.payment.application.VersionedPayment;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class PaymentJdbcLifecycleStore implements PaymentLifecycleStore {

    private static final String FIND_SQL = """
            SELECT id,
                   tenant_id,
                   merchant_id,
                   customer_id,
                   amount,
                   currency,
                   payment_method_category,
                   idempotency_key,
                   status,
                   version
              FROM payment.payments
             WHERE tenant_id = ?
               AND id = ?
            """;

    private static final String COMPARE_AND_SET_SQL = """
            UPDATE payment.payments
               SET status = ?,
                   version = version + 1,
                   updated_at = ?
             WHERE tenant_id = ?
               AND id = ?
               AND version = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    PaymentJdbcLifecycleStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<VersionedPayment> findByTenantAndId(
            UUID tenantId,
            PaymentId paymentId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        return jdbcTemplate.query(
                        FIND_SQL,
                        this::mapVersionedPayment,
                        tenantId,
                        paymentId.value()
                )
                .stream()
                .findFirst();
    }

    @Override
    public boolean compareAndSet(Payment updatedPayment, long expectedVersion) {
        Objects.requireNonNull(updatedPayment, "Updated Payment must not be null");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException(
                    "Expected Payment persistence version must not be negative"
            );
        }

        return jdbcTemplate.update(
                COMPARE_AND_SET_SQL,
                updatedPayment.status().name(),
                Timestamp.from(clock.instant()),
                updatedPayment.tenantId(),
                updatedPayment.id().value(),
                expectedVersion
        ) == 1;
    }

    private VersionedPayment mapVersionedPayment(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        UUID tenantId = resultSet.getObject("tenant_id", UUID.class);
        Payment payment = Payment.rehydrate(
                PaymentId.from(resultSet.getObject("id", UUID.class)),
                MerchantReference.from(
                        tenantId,
                        resultSet.getObject("merchant_id", UUID.class)
                ),
                CustomerId.from(resultSet.getObject("customer_id", UUID.class)),
                Money.of(
                        resultSet.getBigDecimal("amount"),
                        Currency.getInstance(resultSet.getString("currency"))
                ),
                PaymentMethodCategory.from(
                        resultSet.getString("payment_method_category")
                ),
                IdempotencyKey.from(resultSet.getString("idempotency_key")),
                PaymentStatus.valueOf(resultSet.getString("status"))
        );

        return new VersionedPayment(payment, resultSet.getLong("version"));
    }
}
