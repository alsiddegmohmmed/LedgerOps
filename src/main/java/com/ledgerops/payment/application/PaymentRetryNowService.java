package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.payment.api.PaymentDetailsQuery;
import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.payment.api.PaymentOperationConflictException;
import com.ledgerops.provider.api.ProviderManualRetryPort;
import com.ledgerops.provider.api.ProviderRetryAcceleration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentRetryNowService {

    private static final int MAX_ATTEMPTS = 3;

    private final PaymentDetailsQuery payments;
    private final ProviderManualRetryPort provider;
    private final AuditAppendPort audit;
    private final Clock clock;

    public PaymentRetryNowService(
            PaymentDetailsQuery payments,
            ProviderManualRetryPort provider,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.payments = Objects.requireNonNull(payments, "Payment query must not be null");
        this.provider = Objects.requireNonNull(provider, "Provider retry port must not be null");
        this.audit = Objects.requireNonNull(audit, "Audit append port must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Transactional
    public PaymentRetryNowResult retryNow(
            UUID tenantId,
            UUID paymentId,
            boolean confirmation,
            String reason,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireAuthorization(tenantId, authorization);
        if (!confirmation) {
            throw new IllegalArgumentException("Confirmation is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reason is required");
        }
        PaymentDetailsSnapshot payment = payments.findByTenantAndPayment(tenantId, paymentId)
                .orElseThrow(() -> new AuthorizationResourceNotFoundException());
        if (!authorization.allowsMerchant(payment.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!"PROCESSING".equals(payment.state())) {
            throw new PaymentOperationConflictException(
                    "Payment retry is allowed only while the Payment is PROCESSING");
        }
        if (payment.attempts().size() >= MAX_ATTEMPTS) {
            throw new PaymentOperationConflictException(
                    "Payment has reached the maximum of three attempts");
        }

        Instant now = clock.instant();
        ProviderRetryAcceleration acceleration = provider.accelerateSafeRetry(
                        tenantId, paymentId, now)
                .orElseThrow(() -> new PaymentOperationConflictException(
                        "No safe, unapplied Provider retry is currently available"));
        audit.appendAction(
                actor == null ? "system" : actor.issuer(),
                actor == null ? "payment-retry" : actor.subject(),
                actor == null ? "SYSTEM" : actor.principalType(),
                tenantId,
                "payment.retry-now.requested",
                "payment",
                paymentId.toString(),
                reason,
                "{\"workId\":\"" + acceleration.workId() + "\",\"previousDueAt\":\""
                        + acceleration.previousDueAt() + "\",\"dueAt\":\""
                        + acceleration.dueAt() + "\"}",
                authorization.correlationId());
        return new PaymentRetryNowResult(
                paymentId, acceleration.workId(), acceleration.previousDueAt(), acceleration.dueAt());
    }

    private void requireAuthorization(UUID tenantId, AuthorizedRequestContext authorization) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        if (!tenantId.equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!authorization.canRetryPayments()) {
            throw new AuthorizationPermissionDeniedException("payment:retry");
        }
    }
}
