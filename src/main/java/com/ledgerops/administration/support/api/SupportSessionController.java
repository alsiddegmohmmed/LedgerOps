package com.ledgerops.administration.support.api;

import com.ledgerops.RequestCorrelationFilter;
import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.identity.api.SupportSessionPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/platform/support-sessions")
class SupportSessionController {

    private final SupportSessionPort sessions;

    SupportSessionController(SupportSessionPort sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    ResponseEntity<SupportSessionHttpResponse> start(
            @Valid @RequestBody SupportSessionHttpRequest request,
            HttpServletRequest servletRequest
    ) {
        var result = sessions.start(request.toCommand(
                AuthorizedRequestContextRequest.principal(servletRequest),
                correlationId(servletRequest)
        ));
        return ResponseEntity.status(201).body(SupportSessionHttpResponse.from(result));
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
