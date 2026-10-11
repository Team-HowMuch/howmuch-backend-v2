package com.amateur.howmuchbackend.adapter.out.kafka.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateEventPublisherPort;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;
import com.amateur.howmuchbackend.infra.messaging.kafka.AcknowledgedKafkaSender;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateKafkaPublisher implements CertificateEventPublisherPort {
    private final AcknowledgedKafkaSender sender;
    private final ObjectMapper json;
    private final CertificateProperties properties;

    public CertificateKafkaPublisher(
            AcknowledgedKafkaSender sender, ObjectMapper json, CertificateProperties properties) {
        this.sender = sender;
        this.json = json;
        this.properties = properties;
    }

    public void publish(CertificatePublication p) {
        var body = json.createObjectNode();
        body.put("schemaVersion", 1);
        body.put("messageId", p.messageId().toString());
        if (p.result()) {
            var e = p.event();
            body.put("messageType", e.type());
            body.put("sessionId", e.sessionId().toString());
            body.put("sessionVersion", e.version());
            body.put("occurredAt", e.occurredAt().toInstant(ZoneOffset.UTC).toString());
            if (e.connectionRef() != null) body.put("connectionRef", e.connectionRef().toString());
            if (e.failureCode() != null) body.put("failureCode", e.failureCode());
            sender.send(
                    properties.resultTopic(),
                    e.sessionId().toString(),
                    json.writeValueAsString(body));
        } else {
            var c = p.command();
            body.put("messageType", c.type());
            body.put("sessionId", c.sessionId().toString());
            body.put("sessionVersion", c.version());
            body.put("occurredAt", c.occurredAt().toInstant(ZoneOffset.UTC).toString());
            body.put("organization", c.organization());
            if (c.uploadRef() != null) {
                body.put("uploadRef", c.uploadRef().toString());
                body.put("deadlineAt", c.deadline().toInstant(ZoneOffset.UTC).toString());
            }
            sender.send(
                    properties.commandTopic(),
                    c.sessionId().toString(),
                    json.writeValueAsString(body));
        }
    }
}
