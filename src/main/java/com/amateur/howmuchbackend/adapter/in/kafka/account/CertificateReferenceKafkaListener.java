package com.amateur.howmuchbackend.adapter.in.kafka.account;

import static com.amateur.howmuchbackend.adapter.in.kafka.KafkaMessageReader.*;

import com.amateur.howmuchbackend.application.port.in.account.ReceiveCertificateReferenceUseCase;
import com.amateur.howmuchbackend.infra.messaging.kafka.InvalidKafkaMessageException;
import com.amateur.howmuchbackend.infra.messaging.kafka.KafkaProcessingException;

import lombok.RequiredArgsConstructor;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.*;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.certificate-reference",
        name = "enabled",
        havingValue = "true")
public class CertificateReferenceKafkaListener {
    private final ObjectMapper json;
    private final ReceiveCertificateReferenceUseCase receiver;

    @KafkaListener(
            topics =
                    "${howmuch.certificate-reference.command-topic:finance.certificate-reference.command.v1}",
            groupId =
                    "${howmuch.certificate-reference.command-group:howmuch-certificate-reference-v1}",
            containerFactory = "certificateReferenceKafkaListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        var body =
                read(
                        json,
                        record.value(),
                        Set.of(
                                "schemaVersion",
                                "messageType",
                                "messageId",
                                "operationId",
                                "occurredAt"),
                        "REFERENCE_CONNECT");
        var operation = uuid(body, "operationId");
        if (!operation.toString().equals(record.key())) throw invalid();
        try {
            var canonical = new TreeMap<String, Object>();
            body.properties().forEach(e -> canonical.put(e.getKey(), e.getValue()));
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(json.writeValueAsBytes(canonical)));
            if (!receiver.receiveReference(uuid(body, "messageId"), operation, hash))
                throw invalid();
        } catch (InvalidKafkaMessageException invalid) {
            throw invalid;
        } catch (Exception failure) {
            throw new KafkaProcessingException();
        }
    }
}
