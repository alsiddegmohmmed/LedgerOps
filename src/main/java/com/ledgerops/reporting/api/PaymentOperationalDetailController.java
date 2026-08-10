package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.reporting.application.PaymentOperationalDetailService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/payments")
class PaymentOperationalDetailController {

    private final PaymentOperationalDetailService details;

    PaymentOperationalDetailController(PaymentOperationalDetailService details) {
        this.details = details;
    }

    @GetMapping("/{paymentId}")
    ResponseEntity<PaymentOperationalDetail> find(
            @PathVariable UUID tenantId,
            @PathVariable UUID paymentId,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.ok(details.find(
                tenantId,
                paymentId,
                AuthorizedRequestContextRequest.required(httpRequest)));
    }
}
