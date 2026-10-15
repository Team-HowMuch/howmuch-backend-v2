package com.amateur.howmuchbackend.adapter.in.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.adapter.in.kafka.certificate.CertificateKafkaListener;
import com.amateur.howmuchbackend.application.port.in.certificate.ReceiveCertificateMessagesUseCase;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.infra.messaging.kafka.InvalidKafkaMessageException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.ObjectMapper;

import java.util.*;

class CertificateMessageContractTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReceiveCertificateMessagesUseCase useCase =
            mock(ReceiveCertificateMessagesUseCase.class);
    private final CertificateKafkaListener listener = new CertificateKafkaListener(json, useCase);
    private final UUID session = UUID.randomUUID();

    private String failure(String type, String code) {
        var n = json.createObjectNode();
        n.put("schemaVersion", 1); n.put("messageId", UUID.randomUUID().toString());
        n.put("messageType", type); n.put("sessionId", session.toString());
        n.put("sessionVersion", 1); n.put("occurredAt", "2026-10-10T00:00:00Z");
        n.put("failureCode", code);
        return n.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CF-12302", "CF-13031", "CF-99998", "REGISTRATION_REJECTED"})
    void failureCodesCrossTheBoundaryWithoutProviderMessages(String code) {
        when(useCase.receiveResult(any(), any())).thenReturn(true);
        listener.result(new ConsumerRecord<>("result", 0, 0, session.toString(), failure("REGISTRATION_FAILED", code)));
        verify(useCase).receiveResult(argThat(e -> code.equals(e.failureCode())), matches("[0-9a-f]{64}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CF-00000", "CF-12302\n", " CF-12302", "CF-12302 raw-secret", "cf-12302", "CF-1232"})
    void successAndUnsafeFailureValuesAreQuarantined(String code) {
        assertThatThrownBy(() -> listener.result(new ConsumerRecord<>("result", 0, 0, session.toString(), failure("REGISTRATION_FAILED", code))))
                .isInstanceOf(InvalidKafkaMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    void anUncertainOutcomeCannotBeReplacedByAProviderFailure() {
        assertThatThrownBy(() -> listener.result(new ConsumerRecord<>("result", 0, 0, session.toString(), failure("REGISTRATION_UNKNOWN", "CF-12302"))))
                .isInstanceOf(InvalidKafkaMessageException.class);
        var unsafe = (tools.jackson.databind.node.ObjectNode) json.readTree(failure("REGISTRATION_FAILED", "CF-12302"));
        unsafe.put("message", "synthetic-private-response");
        assertThatThrownBy(() -> listener.result(new ConsumerRecord<>("result", 0, 0, session.toString(), unsafe.toString())))
                .isInstanceOf(InvalidKafkaMessageException.class);
        verifyNoInteractions(useCase);
    }

    private String command() {
        var n = json.createObjectNode();
        n.put("schemaVersion", 1);
        n.put("messageId", UUID.randomUUID().toString());
        n.put("messageType", "REGISTER");
        n.put("sessionId", session.toString());
        n.put("sessionVersion", 1);
        n.put("organization", "0020");
        n.put("uploadRef", UUID.randomUUID().toString());
        n.put("occurredAt", "2026-10-10T00:00:00Z");
        n.put("deadlineAt", "2026-10-10T00:10:00Z");
        return n.toString();
    }

    @Test
    void validReceiptIsCommittedThroughUseCase() {
        when(useCase.receiveCommand(any(), any())).thenReturn(true);
        listener.command(new ConsumerRecord<>("command", 0, 0, session.toString(), command()));
        verify(useCase)
                .receiveCommand(any(ReceiveCertificateCommand.class), matches("[0-9a-f]{64}"));
    }

    @Test
    void secretsUnknownFieldsAndWrongKeysAreRejected() {
        String valid = command();
        assertThatThrownBy(
                        () ->
                                listener.command(
                                        new ConsumerRecord<>("command", 0, 0, "wrong", valid)))
                .isInstanceOf(InvalidKafkaMessageException.class);
        var withSecret = json.readTree(valid).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) withSecret).put("password", "fixture");
        assertThatThrownBy(
                        () ->
                                listener.command(
                                        new ConsumerRecord<>(
                                                "command",
                                                0,
                                                0,
                                                session.toString(),
                                                withSecret.toString())))
                .isInstanceOf(InvalidKafkaMessageException.class);
        verifyNoInteractions(useCase);
    }

    @Test
    void conflictingDuplicateIsQuarantined() {
        when(useCase.receiveCommand(any(), any())).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                listener.command(
                                        new ConsumerRecord<>(
                                                "command", 0, 0, session.toString(), command())))
                .isInstanceOf(InvalidKafkaMessageException.class);
    }
}
