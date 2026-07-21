package com.ledgerops.simulator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerops.simulator.webhook.enabled", havingValue = "true")
final class SimulatorWebhookWorker {
    private final SimulatorWebhookDeliveryStore store;
    private final SimulatorWebhookSender sender;
    private final String leaseOwner;

    SimulatorWebhookWorker(
            SimulatorWebhookDeliveryStore store,
            SimulatorWebhookSender sender,
            @Value("${ledgerops.simulator.webhook.owner:${HOSTNAME:local}}") String leaseOwner
    ) {
        this.store = store;
        this.sender = sender;
        this.leaseOwner = leaseOwner;
    }

    @Scheduled(fixedDelayString = "${ledgerops.simulator.webhook.delay-ms:250}")
    void sendOne() {
        store.claimNext(leaseOwner).ifPresent(claim -> {
            try {
                int status = sender.send(claim);
                store.record(claim, status, status >= 400 ? "CORE_HTTP_" + status : null);
            } catch (Exception exception) {
                store.recordTransportFailure(claim, "CORE_TRANSPORT_FAILURE");
            }
        });
    }
}
