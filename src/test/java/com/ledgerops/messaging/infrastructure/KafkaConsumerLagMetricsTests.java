package com.ledgerops.messaging.infrastructure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class KafkaConsumerLagMetricsTests {

    @Test
    void registersOnlyTheThreeApprovedBoundedConsumerAndTopicPairs() {
        var meters = new SimpleMeterRegistry();
        new KafkaConsumerLagMetrics(mock(KafkaAdmin.class), meters);

        assertGauge(meters, "provider-command-consumer-v1",
                "ledgerops.provider.commands.v1");
        assertGauge(meters, "payment-provider-result-consumer-v1",
                "ledgerops.provider.results.v1");
        assertGauge(meters, "payment-retry-command-consumer-v1",
                "ledgerops.payment.commands.v1");
    }

    private void assertGauge(SimpleMeterRegistry meters, String consumer, String topic) {
        assertNotNull(meters.find("ledgerops.kafka.consumer.lag")
                .tag("consumer", consumer).tag("topic", topic).gauge());
    }
}
