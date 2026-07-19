package com.ledgerops.payment.infrastructure;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.application.PaymentCreationStore;
import com.ledgerops.payment.application.StoredPayment;
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
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

@Repository
class PaymentJdbcCreationStore implements PaymentCreationStore {

    private static final String FIND_SQL = """
            SELECT id,
                   tenant_id,
                   merchant_id,
                   customer_id,
                   amount,
                   currency,
                   payment_method_category,
                   idempotency_key,
                   request_fingerprint,
                   status
              FROM payment.payments
             WHERE tenant_id = ?
               AND idempotency_key = ?
            """;

    private static final String INSERT_SQL = """
            INSERT INTO payment.payments (
                id,
                tenant_id,
                merchant_id,
                customer_id,
                amount,
                currency,
                payment_method_category,
                idempotency_key,
                request_fingerprint,
                status,
                version,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    PaymentJdbcCreationStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<StoredPayment> findByTenantAndIdempotencyKey(
            UUID tenantId,
            IdempotencyKey idempotencyKey
    ) {
        return jdbcTemplate.query(
                        FIND_SQL,
                        this::mapStoredPayment,
                        tenantId,
                        idempotencyKey.value()
                )
                .stream()
                .findFirst();
    }

    @Override
    public StoredPayment insertOrFind(
            Payment payment,
            String requestFingerprint
    ) {
        Instant now = clock.instant();
        int inserted = jdbcTemplate.update(
                INSERT_SQL,
                payment.id().value(),
                payment.tenantId(),
                payment.merchantReference().value(),
                payment.customerId().value(),
                payment.amount().amount(),
                payment.amount().currency().getCurrencyCode(),
                payment.paymentMethodCategory().value(),
                payment.idempotencyKey().value(),
                requestFingerprint,
                payment.status().name(),
                Timestamp.from(now),
                Timestamp.from(now)
        );

        if (inserted == 1) {
            return new StoredPayment(payment, requestFingerprint, true);
        }

        return findByTenantAndIdempotencyKey(
                payment.tenantId(),
                payment.idempotencyKey()
        ).map(existing -> new StoredPayment(
                existing.payment(),
                existing.requestFingerprint(),
                false
        )).orElseThrow(() -> new IllegalStateException(
                "Idempotency conflict completed without a visible Payment"
        ));
    }

    private StoredPayment mapStoredPayment(
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

        return new StoredPayment(
                payment,
                resultSet.getString("request_fingerprint"),
                false
        );
    }
}
