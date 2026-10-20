package com.amateur.howmuchbackend.adapter.out.kafka.recap;

import com.amateur.howmuchbackend.application.port.out.recap.RecapEventPublisherPort;
import com.amateur.howmuchbackend.application.recap.model.RecapPublicationMessage;
import com.amateur.howmuchbackend.infra.messaging.kafka.AcknowledgedKafkaSender;
import com.amateur.howmuchbackend.infra.messaging.kafka.KafkaMessagingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.messaging.kafka", name = "enabled", havingValue = "true")
public class RecapKafkaPublisher implements RecapEventPublisherPort {
    private final AcknowledgedKafkaSender sender;
    private final ObjectMapper json;
    private final KafkaMessagingProperties properties;

    @Override
    public void publish(RecapPublicationMessage message) {
        if (message.messageId() == null || message.userId() == null || message.userId() < 1
                || message.yearMonth() == null || message.yearMonth().getYear() < 1
                || message.yearMonth().getYear() > 9999 || message.completedSettlementCount() < 1
                || message.occurredAt() == null) {
            throw new IllegalArgumentException("Recap publication is invalid.");
        }
        var body = json.createObjectNode();
        body.put("messageId", message.messageId().toString());
        body.put("messageType", "RecapPublished");
        body.put("schemaVersion", 1);
        body.put("occurredAt", message.occurredAt().toInstant(ZoneOffset.UTC).toString());
        body.put("userId", message.userId());
        body.put("yearMonth", message.yearMonth().toString());
        body.put("completedSettlementCount", message.completedSettlementCount());
        sender.send(properties.recapPublishedTopic(), message.userId().toString(), json.writeValueAsString(body));
    }
}
