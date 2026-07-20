package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentValidationStartService {

    private final PaymentLifecycleStore lifecycleStore;

    public PaymentValidationStartService(PaymentLifecycleStore lifecycleStore) {
        this.lifecycleStore = lifecycleStore;
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

        return new VersionedPayment(validating, Math.addExact(current.version(), 1));
    }
}
