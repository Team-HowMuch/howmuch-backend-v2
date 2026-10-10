package com.amateur.howmuchbackend.adapter.in.kafka.notification;

import com.amateur.howmuchbackend.adapter.in.kafka.KafkaMessageReader;
import com.amateur.howmuchbackend.application.port.in.notification.DeliverNotificationUseCase;
import com.amateur.howmuchbackend.application.port.in.notification.command.DeliverNotificationCommand;
import com.amateur.howmuchbackend.infra.messaging.kafka.KafkaProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "howmuch.messaging.kafka", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "howmuch.notification", name = "delivery-mode", havingValue = "KAFKA")
public class NotificationDeliveryKafkaListener {
    private static final Set<String> FIELDS = Set.of("messageId", "messageType", "schemaVersion", "occurredAt",
            "deliveryId", "generation", "recipientUserId");
    private final ObjectMapper json;
    private final DeliverNotificationUseCase useCase;

    public NotificationDeliveryKafkaListener(ObjectMapper json, DeliverNotificationUseCase useCase) {
        this.json = json;
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${howmuch.messaging.kafka.notification-delivery-topic}",
            groupId = "${howmuch.messaging.kafka.notification-delivery-group}",
            autoStartup = "${howmuch.messaging.kafka.notification-consumer-enabled:false}",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void deliver(ConsumerRecord<String, String> record) {
        var body = KafkaMessageReader.read(json, record.value(), FIELDS, "NotificationDeliveryRequested");
        long recipient = KafkaMessageReader.number(body, "recipientUserId", 1);
        if (!Long.toString(recipient).equals(record.key())) throw KafkaMessageReader.invalid();
        var deliveryId = KafkaMessageReader.uuid(body, "deliveryId");
        long generation = KafkaMessageReader.number(body, "generation", 0);
        var command = new DeliverNotificationCommand(KafkaMessageReader.uuid(body, "messageId"), deliveryId,
                generation, recipient, KafkaMessageReader.utcTime(body, "occurredAt"));
        try {
            // The synchronous UseCase must commit its durable result before returning.
            useCase.deliver(command);
        } catch (IllegalArgumentException exception) {
            throw KafkaMessageReader.invalid();
        } catch (RuntimeException exception) {
            throw new KafkaProcessingException();
        }
    }
}
