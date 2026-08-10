package com.ledgerops.administration.merchant.api;

import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.administration.api.MerchantLifecyclePort;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/merchants/{merchantId}")
class MerchantLifecycleController {

    private final MerchantLifecyclePort lifecycle;

    MerchantLifecycleController(MerchantLifecyclePort lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/suspend")
    ResponseEntity<MerchantLifecycleHttpResponse> suspend(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            @Valid @RequestBody MerchantLifecycleHttpRequest request,
            HttpServletRequest servletRequest
    ) {
        var result = lifecycle.suspend(request.toCommand(
                tenantId,
                merchantId,
                AuthorizedRequestContextRequest.required(servletRequest),
                AuthorizedRequestContextRequest.principal(servletRequest),
                correlationId(servletRequest)
        ));
        return ResponseEntity.ok(MerchantLifecycleHttpResponse.from(result));
    }

    @PostMapping("/activate")
    ResponseEntity<MerchantLifecycleHttpResponse> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            @Valid @RequestBody MerchantLifecycleHttpRequest request,
            HttpServletRequest servletRequest
    ) {
        var result = lifecycle.activate(request.toCommand(
                tenantId,
                merchantId,
                AuthorizedRequestContextRequest.required(servletRequest),
                AuthorizedRequestContextRequest.principal(servletRequest),
                correlationId(servletRequest)
        ));
        return ResponseEntity.ok(MerchantLifecycleHttpResponse.from(result));
    }

    private UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestCorrelationFilter.CORRELATION_ID);
        String correlation = value instanceof String string
                ? string
                : MDC.get(RequestCorrelationFilter.CORRELATION_ID);
        try {
            return correlation == null ? UUID.randomUUID() : UUID.fromString(correlation);
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID();
        }
    }
}
