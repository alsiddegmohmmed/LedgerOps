package com.ledgerops.payment.application;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.payment.api.PaymentNoteResponse;

import java.util.UUID;

public interface PaymentNotePort {

    PaymentNoteResponse add(
            UUID tenantId,
            UUID paymentId,
            String content,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal principal
    );
}
