package com.amateur.howmuchbackend.infra.messaging.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

public final class SanitizedKafkaDltRecoverer implements ConsumerRecordRecoverer {
    private final AcknowledgedKafkaSender sender;
    private final ObjectMapper json;
    private final KafkaMessagingProperties properties;
    private final Clock clock;

    public SanitizedKafkaDltRecoverer(AcknowledgedKafkaSender sender, ObjectMapper json,
                                     KafkaMessagingProperties properties, Clock clock) {
        this.sender = sender;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        String dlt;
        if (record.topic().equals(properties.notificationDeliveryTopic())) {
            dlt = properties.notificationDeliveryDltTopic();
        } else if (record.topic().equals(properties.recapPublishedTopic())) {
            dlt = properties.recapPublishedDltTopic();
        } else {
            // An unregistered source must never be silently skipped.
            throw new KafkaProcessingException();
        }
        String position = record.topic() + ":" + record.partition() + ":" + record.offset();
        var body = json.createObjectNode();
        body.put("messageId", UUID.nameUUIDFromBytes(position.getBytes(StandardCharsets.UTF_8)).toString());
        body.put("messageType", "KafkaMessageQuarantined");
        body.put("schemaVersion", 1);
        body.put("occurredAt", clock.instant().toString());
        body.put("sourceTopic", record.topic());
        body.put("sourcePartition", record.partition());
        body.put("sourceOffset", record.offset());
        body.put("payloadSha256", hash(record.value() == null ? "" : record.value().toString()));
        body.put("failureCode", "INVALID_CONTRACT");
        // The value, key, arbitrary headers, exception message, and stack trace are not copied.
        sender.send(dlt, position, json.writeValueAsString(body));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }
}
