package com.ledgerops.messaging.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        name = "ledgerops.observability.kafka-lag.enabled",
        havingValue = "true"
)
class KafkaConsumerLagMetrics {

    private static final Map<String, String> GROUP_TOPICS = Map.of(
            "provider-command-consumer-v1", "ledgerops.provider.commands.v1",
            "payment-provider-result-consumer-v1", "ledgerops.provider.results.v1",
            "payment-retry-command-consumer-v1", "ledgerops.payment.commands.v1"
    );

    private final KafkaAdmin kafkaAdmin;
    private final Map<String, AtomicLong> lagByGroup = new LinkedHashMap<>();
    private final Counter refreshFailures;

    KafkaConsumerLagMetrics(KafkaAdmin kafkaAdmin, MeterRegistry meters) {
        this.kafkaAdmin = kafkaAdmin;
        this.refreshFailures = meters.counter(
                "ledgerops.kafka.consumer.lag.refresh", "outcome", "failure");
        GROUP_TOPICS.forEach((consumer, topic) -> {
            AtomicLong lag = new AtomicLong();
            lagByGroup.put(consumer, lag);
            Gauge.builder("ledgerops.kafka.consumer.lag", lag, AtomicLong::get)
                    .tag("consumer", consumer)
                    .tag("topic", topic)
                    .register(meters);
        });
    }

    @Scheduled(fixedDelayString = "${ledgerops.observability.kafka-lag.delay-ms:5000}")
    void refresh() {
        Map<String, Object> configuration = new HashMap<>(
                kafkaAdmin.getConfigurationProperties());
        configuration.put("default.api.timeout.ms", 3_000);
        configuration.put("request.timeout.ms", 3_000);
        AdminClient admin = AdminClient.create(configuration);
        try {
            for (Map.Entry<String, String> entry : GROUP_TOPICS.entrySet()) {
                var committed = admin.listConsumerGroupOffsets(entry.getKey())
                        .partitionsToOffsetAndMetadata().get(3, TimeUnit.SECONDS);
                var description = admin.describeTopics(List.of(entry.getValue()))
                        .allTopicNames().get(3, TimeUnit.SECONDS).get(entry.getValue());
                Map<TopicPartition, OffsetSpec> latestRequest = new LinkedHashMap<>();
                description.partitions().forEach(partition -> latestRequest.put(
                        new TopicPartition(entry.getValue(), partition.partition()),
                        OffsetSpec.latest()));
                Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                        admin.listOffsets(latestRequest).all().get(3, TimeUnit.SECONDS);
                long lag = latest.entrySet().stream()
                        .mapToLong(item -> {
                            var committedOffset = committed.get(item.getKey());
                            long current = committedOffset == null ? 0 : committedOffset.offset();
                            return Math.max(0, item.getValue().offset() - current);
                        })
                        .sum();
                lagByGroup.get(entry.getKey()).set(lag);
            }
        } catch (Exception exception) {
            // Preserve the last successful bounded value. Kafka availability has separate signals.
            refreshFailures.increment();
        } finally {
            admin.close(Duration.ZERO);
        }
    }
}
