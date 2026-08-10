package com.ledgerops.notification.api;

import com.ledgerops.identity.api.AuthorizedRequestContextRequest;
import com.ledgerops.notification.application.WebhookEndpointService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "ledgerops.notification.enabled", havingValue = "true")
@RequestMapping("/api/v1/tenants/{tenantId}/merchants/{merchantId}/webhooks")
class WebhookEndpointController {

    private final WebhookEndpointService service;

    WebhookEndpointController(WebhookEndpointService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<List<WebhookEndpointResponse>> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(service.list(tenantId, merchantId,
                        AuthorizedRequestContextRequest.required(request)).stream()
                .map(WebhookEndpointResponse::from).toList());
    }

    @PostMapping
    ResponseEntity<WebhookSecretResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            @Valid @RequestBody WebhookEndpointRequest body,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookSecretResponse.from(service.create(
                tenantId, merchantId, body.label(), body.endpointUrl(), body.eventTypes(),
                AuthorizedRequestContextRequest.required(request),
                AuthorizedRequestContextRequest.principal(request))));
    }

    @PostMapping("/{endpointId}/rotate")
    ResponseEntity<WebhookSecretResponse> rotate(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(WebhookSecretResponse.from(service.rotate(
                tenantId, merchantId, endpointId,
                AuthorizedRequestContextRequest.required(request),
                AuthorizedRequestContextRequest.principal(request))));
    }

    @DeleteMapping("/{endpointId}")
    ResponseEntity<WebhookEndpointResponse> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(WebhookEndpointResponse.from(service.revoke(
                tenantId, merchantId, endpointId,
                AuthorizedRequestContextRequest.required(request),
                AuthorizedRequestContextRequest.principal(request))));
    }

    @PostMapping("/{endpointId}/test-events")
    ResponseEntity<WebhookDeliveryResponse> trigger(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            @Valid @RequestBody WebhookTestEventRequest body,
            HttpServletRequest request
    ) {
        return ResponseEntity.accepted().body(WebhookDeliveryResponse.from(service.trigger(
                tenantId, merchantId, endpointId, body.type(), body.payload(),
                AuthorizedRequestContextRequest.required(request),
                AuthorizedRequestContextRequest.principal(request))));
    }

    @GetMapping("/{endpointId}/deliveries")
    ResponseEntity<List<WebhookDeliveryResponse>> deliveries(
            @PathVariable UUID tenantId,
            @PathVariable UUID merchantId,
            @PathVariable UUID endpointId,
            HttpServletRequest request
    ) {
        return ResponseEntity.ok(service.deliveries(
                        tenantId, merchantId, endpointId,
                        AuthorizedRequestContextRequest.required(request)).stream()
                .map(WebhookDeliveryResponse::from).toList());
    }
}
