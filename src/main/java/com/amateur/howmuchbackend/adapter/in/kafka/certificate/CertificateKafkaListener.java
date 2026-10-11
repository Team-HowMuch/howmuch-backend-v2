package com.amateur.howmuchbackend.adapter.in.kafka.certificate;

import static com.amateur.howmuchbackend.adapter.in.kafka.KafkaMessageReader.*;

import com.amateur.howmuchbackend.adapter.in.kafka.KafkaMessageReader;
import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.ReceiveCertificateMessagesUseCase;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateResultCommand;
import com.amateur.howmuchbackend.infra.messaging.kafka.KafkaProcessingException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateKafkaListener {
    private static final Set<String> COMMON =
            Set.of(
                    "schemaVersion",
                    "messageId",
                    "messageType",
                    "sessionId",
                    "sessionVersion",
                    "occurredAt");
    private final ObjectMapper json;
    private final ReceiveCertificateMessagesUseCase useCase;

    public CertificateKafkaListener(ObjectMapper json, ReceiveCertificateMessagesUseCase useCase) {
        this.json = json;
        this.useCase = useCase;
    }

    @KafkaListener(
            topics = "${howmuch.certificate.command-topic:certificate.registration.command.v1}",
            groupId = "${howmuch.certificate.command-group:howmuch-certificate-provider-v1}",
            containerFactory = "certificateKafkaListenerContainerFactory")
    public void command(ConsumerRecord<String, String> record) {
        String type = type(record.value());
        if (!Set.of("REGISTER", "CANCEL").contains(type)) throw invalid();
        var fields = new HashSet<>(COMMON);
        fields.add("organization");
        if (type.equals("REGISTER")) fields.addAll(Set.of("uploadRef", "deadlineAt"));
        var body = KafkaMessageReader.read(json, record.value(), fields, type);
        UUID session = uuid(body, "sessionId");
        if (!session.toString().equals(record.key())
                || !text(body, "organization").matches("[0-9]{4}")) throw invalid();
        var c =
                new ReceiveCertificateCommand(
                        uuid(body, "messageId"),
                        session,
                        number(body, "sessionVersion", 1),
                        type,
                        text(body, "organization"),
                        type.equals("REGISTER") ? uuid(body, "uploadRef") : null,
                        type.equals("REGISTER") ? utcTime(body, "deadlineAt") : null,
                        utcTime(body, "occurredAt"));
        if (c.deadline() != null && !c.deadline().isAfter(c.occurredAt())) throw invalid();
        try {
            if (!useCase.receiveCommand(c, hash(canonical(body)))) throw invalid();
        } catch (
                com.amateur.howmuchbackend.infra.messaging.kafka.InvalidKafkaMessageException
                        invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new KafkaProcessingException();
        }
    }

    @KafkaListener(
            topics = "${howmuch.certificate.result-topic:certificate.registration.result.v1}",
            groupId = "${howmuch.certificate.result-group:howmuch-certificate-registration-v1}",
            containerFactory = "certificateKafkaListenerContainerFactory")
    public void result(ConsumerRecord<String, String> record) {
        String type = type(record.value());
        if (!Set.of("REGISTERED", "REGISTRATION_FAILED", "REGISTRATION_UNKNOWN").contains(type))
            throw invalid();
        var fields = new HashSet<>(COMMON);
        fields.add(type.equals("REGISTERED") ? "connectionRef" : "failureCode");
        var body = KafkaMessageReader.read(json, record.value(), fields, type);
        UUID session = uuid(body, "sessionId");
        if (!session.toString().equals(record.key())) throw invalid();
        String code = type.equals("REGISTERED") ? null : text(body, "failureCode");
        if (type.equals("REGISTRATION_UNKNOWN") && !"REGISTRATION_UNCERTAIN".equals(code))
            throw invalid();
        if (type.equals("REGISTRATION_FAILED")
                && !Set.of(
                                "REGISTRATION_REJECTED",
                                "ADDITIONAL_AUTH_REQUIRED",
                                "UPLOAD_EXPIRED",
                                "PROVIDER_UNAVAILABLE",
                                "CONTRACT_CONFLICT")
                        .contains(code)
                && !(code.matches("CF-[0-9]{5}") && !code.equals("CF-00000"))) throw invalid();
        var e =
                new ReceiveCertificateResultCommand(
                        uuid(body, "messageId"),
                        session,
                        number(body, "sessionVersion", 1),
                        type,
                        type.equals("REGISTERED") ? uuid(body, "connectionRef") : null,
                        code,
                        utcTime(body, "occurredAt"));
        try {
            if (!useCase.receiveResult(e, hash(canonical(body)))) throw invalid();
        } catch (
                com.amateur.howmuchbackend.infra.messaging.kafka.InvalidKafkaMessageException
                        invalid) {
            throw invalid;
        } catch (RuntimeException failure) {
            throw new KafkaProcessingException();
        }
    }

    private String type(String value) {
        try {
            if (value == null || value.length() > 4096) throw invalid();
            return text(json.readTree(value), "messageType");
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private String canonical(tools.jackson.databind.JsonNode body) {
        var sorted = new TreeMap<String, String>();
        body.propertyNames().forEach(k -> sorted.put(k, body.path(k).toString()));
        return json.writeValueAsString(sorted);
    }

    private String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
