package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentValidationStartService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PaymentValidationStartService.class
    );

    private final PaymentLifecycleStore lifecycleStore;
    private final PaymentLifecycleEventAppender lifecycleEvents;
    private final Clock clock;

    public PaymentValidationStartService(PaymentLifecycleStore lifecycleStore) {
        this(lifecycleStore, PaymentLifecycleEventAppender.noOp(), Clock.systemUTC());
    }

    @Autowired
    public PaymentValidationStartService(
            PaymentLifecycleStore lifecycleStore,
            PaymentLifecycleEventAppender lifecycleEvents,
            Clock clock
    ) {
        this.lifecycleStore = lifecycleStore;
        this.lifecycleEvents = lifecycleEvents;
        this.clock = clock;
    }

    @Transactional
    public VersionedPayment start(UUID tenantId, PaymentId paymentId) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");

        VersionedPayment current = lifecycleStore.findByTenantAndId(tenantId, paymentId)
                .orElseThrow(() -> new PaymentLifecycleNotFoundException(
                        tenantId,
                        paymentId
                ));
        Payment payment = current.payment();

        if (payment.status() != PaymentStatus.CREATED) {
            throw new PaymentLifecycleStateException(
                    payment.id(),
                    PaymentStatus.CREATED,
                    payment.status()
            );
        }

        Payment validating = payment.startValidation();
        if (!lifecycleStore.compareAndSet(validating, current.version())) {
            throw new PaymentOptimisticConcurrencyException(
                    payment.id(),
                    current.version()
            );
        }

        VersionedPayment result = new VersionedPayment(
                validating,
                Math.addExact(current.version(), 1)
        );
        lifecycleEvents.append(
                payment,
                validating,
                result.version(),
                "AUTOMATED",
                "VALIDATION_STARTED",
                PaymentLifecycleEventFactory.deterministicCorrelation(validating, result.version()),
                PaymentLifecycleEventFactory.deterministicCausation(validating, result.version()),
                clock.instant());
        LOGGER.info(
                "Payment validation started tenantId={} paymentId={} status={} version={}",
                validating.tenantId(),
                validating.id().value(),
                validating.status(),
                result.version()
        );
        return result;
    }
}
