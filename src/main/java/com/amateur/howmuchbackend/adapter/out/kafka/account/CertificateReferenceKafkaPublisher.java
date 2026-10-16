package com.amateur.howmuchbackend.adapter.out.kafka.account;

import com.amateur.howmuchbackend.application.account.model.CertificateReferencePublication;
import com.amateur.howmuchbackend.application.port.out.account.CertificateReferencePublisherPort;
import com.amateur.howmuchbackend.infra.account.config.CertificateReferenceProperties;
import com.amateur.howmuchbackend.infra.messaging.kafka.AcknowledgedKafkaSender;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceKafkaPublisher implements CertificateReferencePublisherPort {
    private final AcknowledgedKafkaSender sender;
    private final ObjectMapper json;
    private final CertificateReferenceProperties properties;

    public void publish(CertificateReferencePublication p) {
        var body = json.createObjectNode();
        body.put("schemaVersion", 1);
        body.put("messageType", "REFERENCE_CONNECT");
        body.put("messageId", p.messageId().toString());
        body.put("operationId", p.operationId().toString());
        body.put("occurredAt", p.occurredAt().toInstant(ZoneOffset.UTC).toString());
        sender.send(
                properties.commandTopic(),
                p.operationId().toString(),
                json.writeValueAsString(body));
    }
}
