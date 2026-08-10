package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.payment.api.PaymentNoteResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
class PaymentNoteService implements PaymentNotePort {

    private final PaymentNoteStore store;
    private final AuditAppendPort audit;
    private final Clock clock;

    PaymentNoteService(PaymentNoteStore store, AuditAppendPort audit, Clock clock) {
        this.store = Objects.requireNonNull(store, "Payment note store must not be null");
        this.audit = Objects.requireNonNull(audit, "Audit append port must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    @Override
    @Transactional
    public PaymentNoteResponse add(
            UUID tenantId,
            UUID paymentId,
            String content,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal principal
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(principal, "Authenticated principal must not be null");
        if (!authorization.isHuman() || !authorization.canAddPaymentNote()) {
            throw new AuthorizationPermissionDeniedException("payment:note-add");
        }
        if (!authorization.tenantId().equals(tenantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
        PaymentNoteStore.PaymentResource payment = store.findPayment(tenantId, paymentId)
                .orElseThrow(() -> new PaymentLifecycleNotFoundException(
                        tenantId, new com.ledgerops.payment.domain.PaymentId(paymentId)));
        if (!authorization.allowsMerchant(payment.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (content == null || content.isBlank() || content.length() > 4000) {
            throw new IllegalArgumentException("Payment note content must contain 1 to 4000 characters");
        }

        PaymentNoteStore.Note note = new PaymentNoteStore.Note(
                UUID.randomUUID(),
                tenantId,
                paymentId,
                payment.merchantId(),
                principal.issuer(),
                principal.subject(),
                content,
                clock.instant());
        store.append(note);
        audit.appendPaymentNoteAdded(
                principal.issuer(),
                principal.subject(),
                tenantId,
                payment.merchantId(),
                paymentId,
                note.noteId(),
                authorization.correlationId());
        return new PaymentNoteResponse(
                note.noteId(),
                note.tenantId(),
                note.paymentId(),
                note.authorIssuer(),
                note.authorSubject(),
                note.content(),
                note.createdAt());
    }
}
