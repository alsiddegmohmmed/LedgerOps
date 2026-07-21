package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderWebhookClaim;
import com.ledgerops.provider.application.ProviderWebhookExecutionStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "ledgerops.provider.webhook.processing.enabled",
        havingValue = "true"
)
class ProviderWebhookWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderWebhookWorker.class);

    private final ProviderWebhookExecutionStore store;
    private final MeterRegistry meters;
    private final String leaseOwner;

    ProviderWebhookWorker(
            ProviderWebhookExecutionStore store,
            MeterRegistry meters,
            @Value("${ledgerops.provider.webhook.processing.owner:${HOSTNAME:local}}")
            String leaseOwner
    ) {
        this.store = store;
        this.meters = meters;
        this.leaseOwner = leaseOwner;
    }

    @Scheduled(fixedDelayString = "${ledgerops.provider.webhook.processing.delay-ms:250}")
    void processOne() {
        store.claimNextWebhook(leaseOwner).ifPresent(this::process);
    }

    private void process(ProviderWebhookClaim claim) {
        var outcome = store.processWebhook(claim);
        meters.counter("ledgerops.provider.webhook.processing",
                "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
        LOGGER.info(
                "Provider webhook processed tenantId={} paymentId={} attemptId={} providerEventId={} outcome={} correlationId={}",
                claim.tenantId(), claim.paymentId(), claim.attemptId(),
                claim.providerEventId(), outcome, claim.correlationId());
    }
}
