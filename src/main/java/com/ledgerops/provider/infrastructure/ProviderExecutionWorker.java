package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.application.ProviderCallResult;
import com.ledgerops.provider.application.ProviderCallDeferredException;
import com.ledgerops.provider.application.ProviderExecutionStore;
import com.ledgerops.provider.application.ProviderGateway;
import com.ledgerops.provider.application.ProviderWorkClaim;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerops.provider.execution.enabled", havingValue = "true")
class ProviderExecutionWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderExecutionWorker.class);
    private final ProviderExecutionStore store;
    private final ProviderGateway gateway;
    private final MeterRegistry meters;
    private final String owner;

    ProviderExecutionWorker(ProviderExecutionStore store, ProviderGateway gateway,
            MeterRegistry meters,
            @Value("${ledgerops.provider.execution.owner:${HOSTNAME:local}}") String owner) {
        this.store = store;
        this.gateway = gateway;
        this.meters = meters;
        this.owner = owner;
    }

    @Scheduled(fixedDelayString = "${ledgerops.provider.execution.delay-ms:250}")
    void executeOne() {
        if (store.claimRetryRequest(owner).map(claim -> {
            store.issueRetryRequest(claim);
            return true;
        }).orElse(false)) {
            return;
        }
        store.claimNext(owner).ifPresent(this::execute);
    }

    private void execute(ProviderWorkClaim claim) {
        if (claim.exhausted()) {
            store.markUnresolved(claim, "STATUS_RECOVERY_EXHAUSTED");
            return;
        }
        if (claim.recoveryOnly()) {
            store.markAmbiguous(claim, "CRASH_AFTER_POSSIBLE_SUBMISSION");
            meters.counter("ledgerops.provider.ambiguity").increment();
            return;
        }
        ProviderCallResult result;
        try {
            result = gateway.execute(claim);
        } catch (ProviderCallDeferredException deferred) {
            store.defer(claim, deferred.reasonCode());
            meters.counter("ledgerops.provider.deferred", "provider", "SIMULATOR",
                    "reason", deferred.reasonCode()).increment();
            return;
        }
        store.record(claim, result);
        String outcome = result.communicationOutcome().toLowerCase(java.util.Locale.ROOT);
        meters.timer("ledgerops.provider.http.duration", "operation",
                claim.workType().name().toLowerCase(java.util.Locale.ROOT),
                "outcome", outcome).record(
                java.time.Duration.ofMillis(result.latencyMillis()));
        meters.counter("ledgerops.provider.result",
                "category", result.category().name()).increment();
        if ("TIMEOUT".equals(result.communicationOutcome())) {
            meters.counter("ledgerops.provider.timeout", "operation",
                    claim.workType().name().toLowerCase(java.util.Locale.ROOT)).increment();
        }
        if (result.category() == ProviderResultCategory.UNKNOWN) {
            meters.counter("ledgerops.provider.ambiguity").increment();
        }
        LOGGER.info("Provider evidence persisted tenantId={} paymentId={} attemptId={} category={} disposition={} correlationId={}",
                claim.tenantId(), claim.paymentId(), claim.attemptId(), result.category(),
                result.disposition(), claim.correlationId());
    }

}
