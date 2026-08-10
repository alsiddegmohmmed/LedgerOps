package com.ledgerops.notification.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.notification.api.WebhookDelivery;
import com.ledgerops.notification.api.WebhookEndpoint;
import com.ledgerops.notification.api.WebhookEndpointPort;
import com.ledgerops.notification.api.WebhookEventType;
import com.ledgerops.notification.api.WebhookSecretResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnBean(WebhookEndpointPort.class)
public class WebhookEndpointService {

    private final WebhookEndpointPort endpoints;
    private final AuditAppendPort audit;
    private final Clock clock;

    public WebhookEndpointService(
            WebhookEndpointPort endpoints,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.endpoints = Objects.requireNonNull(endpoints, "Webhook endpoint port must not be null");
        this.audit = Objects.requireNonNull(audit, "Audit append port must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    public WebhookSecretResult create(
            UUID tenantId,
            UUID merchantId,
            String label,
            String endpointUrl,
            Set<WebhookEventType> events,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireManage(tenantId, merchantId, authorization);
        WebhookSecretResult result = endpoints.create(
                tenantId, merchantId, label, endpointUrl, events, clock.instant());
        audit(actor, tenantId, "notification.webhook-endpoint.created",
                result.endpoint().endpointId(), "Webhook endpoint created");
        return result;
    }

    public WebhookSecretResult rotate(
            UUID tenantId,
            UUID merchantId,
            UUID endpointId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireManage(tenantId, merchantId, authorization);
        WebhookSecretResult result = endpoints.rotate(tenantId, merchantId, endpointId, clock.instant());
        audit(actor, tenantId, "notification.webhook-endpoint.rotated", endpointId,
                "Webhook endpoint secret rotated");
        return result;
    }

    public WebhookEndpoint revoke(
            UUID tenantId,
            UUID merchantId,
            UUID endpointId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireManage(tenantId, merchantId, authorization);
        WebhookEndpoint endpoint = endpoints.revoke(
                tenantId, merchantId, endpointId, clock.instant());
        audit(actor, tenantId, "notification.webhook-endpoint.revoked", endpointId,
                "Webhook endpoint revoked");
        return endpoint;
    }

    public List<WebhookEndpoint> list(
            UUID tenantId,
            UUID merchantId,
            AuthorizedRequestContext authorization
    ) {
        requireRead(tenantId, merchantId, authorization);
        return endpoints.list(tenantId, merchantId);
    }

    public WebhookDelivery trigger(
            UUID tenantId,
            UUID merchantId,
            UUID endpointId,
            WebhookEventType eventType,
            Map<String, Object> payload,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        requireTrigger(tenantId, merchantId, authorization);
        WebhookDelivery delivery = endpoints.trigger(
                tenantId, merchantId, endpointId, eventType, payload, clock.instant());
        audit(actor, tenantId, "notification.webhook-test.triggered", endpointId,
                "Synthetic webhook delivery created");
        return delivery;
    }

    public List<WebhookDelivery> deliveries(
            UUID tenantId,
            UUID merchantId,
            UUID endpointId,
            AuthorizedRequestContext authorization
    ) {
        requireRead(tenantId, merchantId, authorization);
        return endpoints.deliveries(tenantId, merchantId, endpointId);
    }

    private void requireManage(UUID tenantId, UUID merchantId, AuthorizedRequestContext authorization) {
        requireScope(tenantId, merchantId, authorization);
        if (!authorization.canManageWebhookEndpoints()) {
            throw new AuthorizationPermissionDeniedException("webhook:endpoint-manage");
        }
    }

    private void requireTrigger(UUID tenantId, UUID merchantId, AuthorizedRequestContext authorization) {
        requireScope(tenantId, merchantId, authorization);
        if (!authorization.canTriggerWebhookTests()) {
            throw new AuthorizationPermissionDeniedException("webhook:test-trigger");
        }
    }

    private void requireRead(UUID tenantId, UUID merchantId, AuthorizedRequestContext authorization) {
        requireScope(tenantId, merchantId, authorization);
        if (!authorization.canReadNotifications()
                && !authorization.canManageWebhookEndpoints()) {
            throw new AuthorizationPermissionDeniedException("notification:read");
        }
    }

    private void requireScope(UUID tenantId, UUID merchantId, AuthorizedRequestContext authorization) {
        if (!tenantId.equals(authorization.tenantId()) || !authorization.allowsMerchant(merchantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private void audit(
            AuthenticatedPrincipal actor,
            UUID tenantId,
            String action,
            UUID endpointId,
            String details
    ) {
        audit.appendAction(
                actor == null ? "system" : actor.issuer(), actor == null ? "notification" : actor.subject(),
                actor == null ? "SYSTEM" : actor.principalType(), tenantId, action,
                "webhook-endpoint", endpointId.toString(), details, "{}", null);
    }
}
