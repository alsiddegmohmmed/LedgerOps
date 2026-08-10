package com.ledgerops.payment.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.payment.application.ReversalRequestCommand;
import com.ledgerops.payment.application.ReversalRequestService;
import com.ledgerops.payment.application.ReversalRetryCommand;
import com.ledgerops.payment.application.ReversalRetryService;
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
@RequestMapping("/api/v1/tenants/{tenantId}/reversals")
class ReversalController {

    private final ReversalRequestService requests;
    private final ReversalRetryService retries;

    ReversalController(ReversalRequestService requests, ReversalRetryService retries) {
        this.requests = requests;
        this.retries = retries;
    }

    @PostMapping
    ResponseEntity<ReversalResponse> request(
            @PathVariable UUID tenantId,
            @Valid @RequestBody ReversalRequest body,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var result = requests.request(new ReversalRequestCommand(
                tenantId,
                body.paymentId(),
                body.confirmation(),
                body.reason(),
                authorization,
                AuthorizedRequestContextRequest.principal(httpRequest)
        ));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReversalResponse.from(result.reversal()));
    }

    @PostMapping("/{reversalId}/retry")
    ResponseEntity<ReversalRetryResponse> retry(
            @PathVariable UUID tenantId,
            @PathVariable UUID reversalId,
            @Valid @RequestBody ReversalRetryRequest body,
            HttpServletRequest httpRequest
    ) {
        var authorization = AuthorizedRequestContextRequest.required(httpRequest);
        var result = retries.retry(new ReversalRetryCommand(
                tenantId,
                body.paymentId(),
                reversalId,
                body.previousAttemptId(),
                body.providerEvidenceId(),
                "SIMULATOR",
                body.confirmation(),
                body.reason(),
                authorization,
                AuthorizedRequestContextRequest.principal(httpRequest)
        ));
        return ResponseEntity.ok(ReversalRetryResponse.from(reversalId, result));
    }
}
