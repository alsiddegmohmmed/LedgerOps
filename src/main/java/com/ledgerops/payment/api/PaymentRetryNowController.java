package com.ledgerops.payment.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.payment.application.PaymentRetryNowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/payments/{paymentId}/retry")
class PaymentRetryNowController {

    private final PaymentRetryNowService service;

    PaymentRetryNowController(PaymentRetryNowService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<PaymentRetryNowResponse> retryNow(
            @PathVariable UUID tenantId,
            @PathVariable UUID paymentId,
            @Valid @RequestBody PaymentRetryNowRequest body,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(PaymentRetryNowResponse.from(service.retryNow(
                tenantId, paymentId, body.confirmation(), body.reason(),
                AuthorizedRequestContextRequest.required(request),
                AuthorizedRequestContextRequest.principal(request))));
    }
}
