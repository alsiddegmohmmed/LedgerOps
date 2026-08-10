package com.ledgerops.risk.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/risk/configuration")
class RiskConfigurationController {

    private final com.ledgerops.risk.application.RiskConfigurationService service;

    RiskConfigurationController(com.ledgerops.risk.application.RiskConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    RiskConfigurationHttpResponse current(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return RiskConfigurationHttpResponse.from(service.current(
                tenantId, AuthorizedRequestContextRequest.required(request)));
    }

    @GetMapping("/history")
    List<RiskConfigurationHttpResponse> history(
            @PathVariable UUID tenantId,
            HttpServletRequest request
    ) {
        return service.history(tenantId, AuthorizedRequestContextRequest.required(request))
                .stream().map(RiskConfigurationHttpResponse::from).toList();
    }

    @PutMapping
    ResponseEntity<RiskConfigurationHttpResponse> update(
            @PathVariable UUID tenantId,
            @Valid @RequestBody RiskConfigurationHttpRequest body,
            HttpServletRequest request
    ) {
        var authorization = AuthorizedRequestContextRequest.required(request);
        var result = service.update(
                tenantId, body.reviewThreshold(), body.rejectThreshold(), body.toConfigurations(),
                body.expectedVersion(), body.confirmation(), body.reason(), authorization,
                AuthorizedRequestContextRequest.principal(request));
        return ResponseEntity.ok(RiskConfigurationHttpResponse.from(result));
    }
}
