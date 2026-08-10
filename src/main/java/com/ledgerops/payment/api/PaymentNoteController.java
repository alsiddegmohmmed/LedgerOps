package com.ledgerops.payment.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.payment.application.PaymentNotePort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/payments/{paymentId}/notes")
class PaymentNoteController {

    private final PaymentNotePort notes;

    PaymentNoteController(PaymentNotePort notes) {
        this.notes = notes;
    }

    @PostMapping
    ResponseEntity<PaymentNoteResponse> add(
            @PathVariable UUID tenantId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentNoteRequest request,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var principal = AuthorizedRequestContextRequest.principal(httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(notes.add(
                tenantId,
                paymentId,
                request.content(),
                authorization,
                principal));
    }
}
