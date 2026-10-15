package com.amateur.howmuchbackend.infra.messaging.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

@ConfigurationProperties("howmuch.messaging.kafka")
public record KafkaMessagingProperties(
        boolean enabled,
        Duration publishTimeout,
        Duration processingRetryInterval,
        int concurrency,
        String notificationDeliveryTopic,
        String notificationDeliveryDltTopic,
        String notificationDeliveryGroup,
        boolean notificationConsumerEnabled,
        String recapPublishedTopic,
        String recapPublishedDltTopic,
        String recapPublishedGroup,
        boolean recapConsumerEnabled
) {
    public KafkaMessagingProperties {
        publishTimeout = publishTimeout == null ? Duration.ofSeconds(5) : publishTimeout;
        processingRetryInterval = processingRetryInterval == null ? Duration.ofSeconds(1) : processingRetryInterval;
        concurrency = concurrency == 0 ? 1 : concurrency;
        notificationDeliveryTopic = defaultName(notificationDeliveryTopic, "notification.delivery.v1");
        notificationDeliveryDltTopic = defaultName(notificationDeliveryDltTopic, "notification.delivery.v1.dlt");
        notificationDeliveryGroup = defaultName(notificationDeliveryGroup, "howmuch-notification-delivery-v1");
        recapPublishedTopic = defaultName(recapPublishedTopic, "recap.published.v1");
        recapPublishedDltTopic = defaultName(recapPublishedDltTopic, "recap.published.v1.dlt");
        recapPublishedGroup = defaultName(recapPublishedGroup, "howmuch-recap-notification-v1");
        if (publishTimeout.toMillis() < 1 || publishTimeout.compareTo(Duration.ofMinutes(1)) > 0
                || processingRetryInterval.toMillis() < 1
                || processingRetryInterval.compareTo(Duration.ofSeconds(10)) > 0
                || concurrency < 1 || concurrency > 32) {
            throw new IllegalArgumentException("Kafka transport limits are invalid.");
        }
        if (new HashSet<>(List.of(notificationDeliveryTopic, notificationDeliveryDltTopic, recapPublishedTopic,
                recapPublishedDltTopic)).size() != 4) {
            throw new IllegalArgumentException("Kafka source and quarantine topics must be distinct.");
        }
    }

    private static String defaultName(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value;
        if (!result.matches("[a-zA-Z0-9._-]{1,249}")) {
            throw new IllegalArgumentException("Kafka topic or group name is invalid.");
        }
        return result;
    }
}
