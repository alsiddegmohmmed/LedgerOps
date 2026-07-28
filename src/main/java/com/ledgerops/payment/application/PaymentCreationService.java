package com.ledgerops.payment.application;

import com.ledgerops.customer.api.CustomerActivityQuery;
import com.ledgerops.customer.api.CustomerActivityStatus;
import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
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
    private final AuditAppendPort auditAppendPort;

    public PaymentCreationService(
            PaymentCreationStore paymentStore,
            TenantActivityQuery tenantActivityQuery,
            MerchantActivityQuery merchantActivityQuery,
            CustomerActivityQuery customerActivityQuery,
            AuditAppendPort auditAppendPort
    ) {
        this.paymentStore = paymentStore;
        this.tenantActivityQuery = tenantActivityQuery;
        this.merchantActivityQuery = merchantActivityQuery;
        this.customerActivityQuery = customerActivityQuery;
        this.auditAppendPort = auditAppendPort;
    }

    @Transactional
    public PaymentCreationResult createPayment(CreatePaymentCommand command) {
        return createPayment(command, null, null);
    }

    @Transactional
    public PaymentCreationResult createPayment(
            CreatePaymentCommand command,
            AuthorizedRequestContext context,
            AuthenticatedPrincipal principal
    ) {
        Objects.requireNonNull(command, "Create payment command must not be null");
        if (context != null) {
            authorize(command, context);
        }

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

        PaymentCreationResult result = paymentStore.findByTenantAndIdempotencyKey(
                        command.tenantId(),
                        idempotencyKey
                )
                .map(existing -> resolve(existing, requestFingerprint))
                .orElseGet(() -> createAfterValidation(
                        requestedPayment,
                        requestFingerprint
                ));
        if (result.created() && context != null && principal != null) {
            auditAppendPort.appendPaymentCreated(
                    principal.issuer(),
                    principal.subject(),
                    principal.principalType(),
                    context.tenantId(),
                    result.payment().id().value(),
                    context.correlationId()
            );
        }
        return result;
    }

    private void authorize(CreatePaymentCommand command, AuthorizedRequestContext context) {
        Objects.requireNonNull(context, "Authorized request context must not be null");
        if (!context.tenantId().equals(command.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!context.canCreatePayment()) {
            throw new AuthorizationPermissionDeniedException("payment:create");
        }
        if (!context.includesMerchant(command.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
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
