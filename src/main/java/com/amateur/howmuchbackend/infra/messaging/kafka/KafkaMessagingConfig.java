package com.amateur.howmuchbackend.infra.messaging.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaMessagingProperties.class)
public class KafkaMessagingConfig {
    @Bean
    InitializingBean validateKafkaModes(KafkaMessagingProperties properties, KafkaProperties kafka, Environment env) {
        return () -> {
            String mode = env.getProperty("howmuch.notification.delivery-mode", "DIRECT");
            if (!Set.of("DIRECT", "KAFKA").contains(mode)) {
                throw new IllegalArgumentException("Notification delivery mode must be DIRECT or KAFKA.");
            }
            if ((mode.equals("KAFKA") || properties.recapConsumerEnabled()) && !properties.enabled()) {
                throw new IllegalArgumentException("Kafka delivery and recap consumers require enabled Kafka transport.");
            }
            if (properties.notificationConsumerEnabled() && (!mode.equals("KAFKA") || !properties.enabled())) {
                throw new IllegalArgumentException("Notification Kafka consumers require enabled transport and KAFKA mode.");
            }
            if (properties.enabled()) requireBootstrap(kafka);
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "howmuch.messaging.kafka", name = "enabled", havingValue = "true")
    AcknowledgedKafkaSender acknowledgedKafkaSender(KafkaTemplate<String, String> template,
                                                   KafkaMessagingProperties properties,
                                                   KafkaProperties kafka, @Nullable MeterRegistry meters) {
        requireBootstrap(kafka);
        // The caller records failures; the default producer logger would copy record keys and values.
        template.setProducerListener(new ProducerListener<>() {});
        template.setObservationEnabled(true);
        return new AcknowledgedKafkaSender(template, properties.publishTimeout(), meters);
    }

    @Bean
    @ConditionalOnProperty(prefix = "howmuch.messaging.kafka", name = "enabled", havingValue = "true")
    DefaultErrorHandler kafkaContractErrorHandler(AcknowledgedKafkaSender sender, ObjectMapper json,
                                                 KafkaMessagingProperties properties, Clock clock) {
        var handler = new DefaultErrorHandler(new SanitizedKafkaDltRecoverer(sender, json, properties, clock),
                new FixedBackOff(properties.processingRetryInterval().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
        // Only malformed wire contracts are quarantined. A DB outage remains retryable indefinitely.
        handler.addNotRetryableExceptions(InvalidKafkaMessageException.class);
        handler.setAckAfterHandle(true);
        return handler;
    }

    @Bean("durableKafkaListenerContainerFactory")
    @ConditionalOnProperty(prefix = "howmuch.messaging.kafka", name = "enabled", havingValue = "true")
    ConcurrentKafkaListenerContainerFactory<String, String> durableKafkaListenerContainerFactory(
            KafkaProperties kafka, KafkaMessagingProperties properties, DefaultErrorHandler errorHandler,
            @Nullable MeterRegistry meters) {
        return DurableKafkaListenerFactories.create(kafka, properties, errorHandler, meters);
    }

    private void requireBootstrap(KafkaProperties kafka) {
        if (kafka.getBootstrapServers() == null || kafka.getBootstrapServers().isEmpty()
                || kafka.getBootstrapServers().stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Enabled Kafka requires explicit bootstrap servers.");
        }
    }
}
