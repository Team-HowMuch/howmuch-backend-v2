package com.amateur.howmuchbackend.adapter.out.kafka.notification;

import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort;
import com.amateur.howmuchbackend.infra.messaging.kafka.AcknowledgedKafkaSender;
import com.amateur.howmuchbackend.infra.messaging.kafka.KafkaMessagingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;

@Component
@ConditionalOnProperty(prefix = "howmuch.messaging.kafka", name = "enabled", havingValue = "true")
public class NotificationDeliveryKafkaPublisher implements NotificationDeliveryPublisherPort {
    private final AcknowledgedKafkaSender sender;
    private final ObjectMapper json;
    private final KafkaMessagingProperties properties;

    public NotificationDeliveryKafkaPublisher(AcknowledgedKafkaSender sender, ObjectMapper json,
                                             KafkaMessagingProperties properties) {
        this.sender = sender;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public void publish(DeliveryMessage message) {
        if (message.messageId() == null || message.deliveryId() == null || message.occurredAt() == null
                || message.generation() < 0 || message.recipientUserId() == null || message.recipientUserId() < 1) {
            throw new IllegalArgumentException("Notification delivery publication is invalid.");
        }
        var body = json.createObjectNode();
        body.put("messageId", message.messageId().toString());
        body.put("messageType", "NotificationDeliveryRequested");
        body.put("schemaVersion", 1);
        body.put("occurredAt", message.occurredAt().toInstant(ZoneOffset.UTC).toString());
        body.put("deliveryId", message.deliveryId().toString());
        body.put("generation", message.generation());
        body.put("recipientUserId", message.recipientUserId());
        sender.send(properties.notificationDeliveryTopic(), message.recipientUserId().toString(),
                json.writeValueAsString(body));
    }
}
