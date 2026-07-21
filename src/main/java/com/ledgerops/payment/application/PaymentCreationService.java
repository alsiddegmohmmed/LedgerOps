package com.ledgerops.payment.application;

import com.ledgerops.customer.api.CustomerActivityQuery;
import com.ledgerops.customer.api.CustomerActivityStatus;
import com.ledgerops.merchant.api.MerchantActivityQuery;
import com.ledgerops.merchant.api.MerchantActivityStatus;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.tenancy.api.TenantActivityQuery;
import com.ledgerops.tenancy.api.TenantActivityStatus;
import com.ledgerops.tenancy.api.TenantReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.Objects;

@Service
public class PaymentCreationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaymentCreationService.class);

    private final PaymentCreationStore paymentStore;
    private final TenantActivityQuery tenantActivityQuery;
    private final MerchantActivityQuery merchantActivityQuery;
    private final CustomerActivityQuery customerActivityQuery;

    public PaymentCreationService(
            PaymentCreationStore paymentStore,
            TenantActivityQuery tenantActivityQuery,
            MerchantActivityQuery merchantActivityQuery,
            CustomerActivityQuery customerActivityQuery
    ) {
        this.paymentStore = paymentStore;
        this.tenantActivityQuery = tenantActivityQuery;
        this.merchantActivityQuery = merchantActivityQuery;
        this.customerActivityQuery = customerActivityQuery;
    }

    @Transactional
    public PaymentCreationResult createPayment(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "Create payment command must not be null");

        MerchantReference merchantReference = MerchantReference.from(
                command.tenantId(),
                command.merchantId()
        );
        IdempotencyKey idempotencyKey = IdempotencyKey.from(command.idempotencyKey());
        Payment requestedPayment = Payment.create(
                PaymentId.newId(),
                merchantReference,
                CustomerId.from(command.customerId()),
                Money.of(command.amount(), Currency.getInstance(command.currency())),
                PaymentMethodCategory.from(command.paymentMethodCategory()),
                idempotencyKey
        );
        String requestFingerprint = PaymentRequestFingerprint.from(requestedPayment);

        return paymentStore.findByTenantAndIdempotencyKey(
                        command.tenantId(),
                        idempotencyKey
                )
                .map(existing -> resolve(existing, requestFingerprint))
                .orElseGet(() -> createAfterValidation(
                        requestedPayment,
                        requestFingerprint
                ));
    }

    private PaymentCreationResult createAfterValidation(
            Payment requestedPayment,
            String requestFingerprint
    ) {
        validateReferences(requestedPayment);
        StoredPayment stored = paymentStore.insertOrFind(
                requestedPayment,
                requestFingerprint
        );
        return resolve(stored, requestFingerprint);
    }

    private PaymentCreationResult resolve(
            StoredPayment stored,
            String requestFingerprint
    ) {
        Payment payment = stored.payment();

        if (!stored.requestFingerprint().equals(requestFingerprint)) {
            LOGGER.warn(
                    "Payment idempotency conflict tenantId={} paymentId={}",
                    payment.tenantId(),
                    payment.id().value()
            );
            throw new PaymentIdempotencyConflictException(payment.tenantId());
        }

        LOGGER.info(
                "Payment creation {} tenantId={} merchantId={} paymentId={} status={}",
                stored.created() ? "created" : "replayed",
                payment.tenantId(),
                payment.merchantReference().value(),
                payment.id().value(),
                payment.status()
        );
        return new PaymentCreationResult(payment, stored.created());
    }

    private void validateReferences(Payment payment) {
        TenantActivityStatus tenantStatus = tenantActivityQuery.evaluateForUpdate(
                TenantReference.from(payment.tenantId())
        );
        if (tenantStatus != TenantActivityStatus.ALLOWED) {
            throw unavailable(PaymentReferenceType.TENANT, tenantStatus.name());
        }

        MerchantActivityStatus merchantStatus = merchantActivityQuery.evaluate(
                payment.merchantReference()
        );
        if (merchantStatus != MerchantActivityStatus.ALLOWED) {
            throw unavailable(PaymentReferenceType.MERCHANT, merchantStatus.name());
        }

        CustomerActivityStatus customerStatus = customerActivityQuery.evaluate(
                payment.merchantReference(),
                payment.customerId().value()
        );
        if (customerStatus != CustomerActivityStatus.ALLOWED) {
            throw unavailable(PaymentReferenceType.CUSTOMER, customerStatus.name());
        }
    }

    private PaymentReferenceUnavailableException unavailable(
            PaymentReferenceType type,
            String reason
    ) {
        return new PaymentReferenceUnavailableException(type, reason);
    }
}
