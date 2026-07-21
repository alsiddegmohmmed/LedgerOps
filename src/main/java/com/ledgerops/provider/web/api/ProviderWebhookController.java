package com.ledgerops.provider.web.api;

import com.ledgerops.provider.application.ProviderWebhookRequest;
import com.ledgerops.provider.application.ReceiveProviderWebhook;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "ledgerops.provider.webhook.enabled", havingValue = "true")
class ProviderWebhookController {
    private static final String PATH = "/internal/provider/v1/webhooks";

    private final ReceiveProviderWebhook receiver;
    private final Clock clock;

    ProviderWebhookController(ReceiveProviderWebhook receiver, Clock clock) {
        this.receiver = receiver;
        this.clock = clock;
    }

    @PostMapping(path = PATH, consumes = "application/json")
    ResponseEntity<Void> receive(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-LedgerOps-Key-Id", required = false) String keyId,
            @RequestHeader(value = "X-LedgerOps-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-LedgerOps-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-LedgerOps-Signature", required = false) String signature
    ) throws IOException {
        byte[] body = httpRequest.getInputStream()
                .readNBytes(ReceiveProviderWebhook.MAXIMUM_BODY_BYTES + 1);
        UUID correlationId = UUID.fromString(MDC.get("correlationId"));
        var result = receiver.receive(new ProviderWebhookRequest(
                body, keyId, timestamp, eventId, signature,
                correlationId, clock.instant()));
        return switch (result.outcome()) {
            case UNAUTHORIZED -> ResponseEntity.status(401).build();
            case INVALID_PAYLOAD -> ResponseEntity.badRequest().build();
            case CONFLICT -> ResponseEntity.status(409).build();
            case TOO_LARGE -> ResponseEntity.status(413).build();
            case UNMAPPED, ACCEPTED, DUPLICATE -> ResponseEntity.accepted().build();
        };
    }
}
