package com.ledgerops.messaging.infrastructure;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class KafkaTopicConfiguration {

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.messaging.publisher.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    NewTopic providerCommandsTopic() {
        return TopicBuilder.name("ledgerops.provider.commands.v1")
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.messaging.publisher.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    NewTopic providerResultsTopic() {
        return TopicBuilder.name("ledgerops.provider.results.v1")
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    @ConditionalOnProperty(
            name = "ledgerops.messaging.publisher.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    NewTopic paymentLifecycleTopic() {
        return TopicBuilder.name("ledgerops.payment.lifecycle.v1")
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object>
            providerCommandKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory
            ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object>
            paymentResultKafkaListenerContainerFactory(
                    ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
                    ConsumerFactory<Object, Object> consumerFactory
            ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
