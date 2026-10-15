package com.amateur.howmuchbackend.adapter.out.kafka;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amateur.howmuchbackend.adapter.out.kafka.certificate.CertificateKafkaPublisher;
import com.amateur.howmuchbackend.application.certificate.model.CertificatePublication;
import com.amateur.howmuchbackend.application.port.in.certificate.command.*;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;
import com.amateur.howmuchbackend.infra.messaging.kafka.AcknowledgedKafkaSender;
import com.networknt.schema.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.*;

class CertificateWireSchemaTest {
    @ParameterizedTest
    @CsvSource({"CF-12302,true", "CF-13031,true", "CF-99998,true", "REGISTRATION_REJECTED,true", "CF-00000,false", "CF-1232,false", "raw-secret,false"})
    void resultSchemaAllowsSafeProviderCodesAndLegacyReplays(String code, boolean valid) {
        var json = new ObjectMapper();
        var body = json.createObjectNode();
        body.put("schemaVersion", 1); body.put("messageId", UUID.randomUUID().toString());
        body.put("messageType", "REGISTRATION_FAILED"); body.put("sessionId", UUID.randomUUID().toString());
        body.put("sessionVersion", 1); body.put("occurredAt", "2026-10-11T00:00:00Z"); body.put("failureCode", code);
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(SchemaLocation.of("classpath:schemas/certificate-result-v1.schema.json"));
        assertThat(schema.validate(body.toString(), InputFormat.JSON).isEmpty()).isEqualTo(valid);
        body.put("messageType", "REGISTRATION_UNKNOWN");
        assertThat(schema.validate(body.toString(), InputFormat.JSON)).isNotEmpty();
    }

    @Test
    void everyPublishedVariantConformsToThePublicSchema() {
        var sender = mock(AcknowledgedKafkaSender.class);
        var json = new ObjectMapper();
        var props =
                new CertificateProperties(
                        true, "", "", "", List.of(), null, null, null, null, null, null);
        var publisher = new CertificateKafkaPublisher(sender, json, props);
        UUID session = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 0, 0);
        for (String type :
                List.of(
                        "REGISTER",
                        "CANCEL",
                        "REGISTERED",
                        "REGISTRATION_FAILED",
                        "REGISTRATION_UNKNOWN")) {
            UUID id = UUID.randomUUID();
            boolean result = !Set.of("REGISTER", "CANCEL").contains(type);
            var c =
                    result
                            ? null
                            : new ReceiveCertificateCommand(
                                    id,
                                    session,
                                    1,
                                    type,
                                    "0020",
                                    type.equals("REGISTER") ? UUID.randomUUID() : null,
                                    type.equals("REGISTER") ? now.plusMinutes(10) : null,
                                    now);
            var e =
                    result
                            ? new ReceiveCertificateResultCommand(
                                    id,
                                    session,
                                    1,
                                    type,
                                    type.equals("REGISTERED") ? UUID.randomUUID() : null,
                                    type.equals("REGISTRATION_FAILED")
                                            ? "REGISTRATION_REJECTED"
                                            : type.equals("REGISTRATION_UNKNOWN")
                                                    ? "REGISTRATION_UNCERTAIN"
                                                    : null,
                                    now)
                            : null;
            publisher.publish(new CertificatePublication(result, id, UUID.randomUUID(), 1, c, e));
            var payload = ArgumentCaptor.forClass(String.class);
            verify(sender).send(anyString(), eq(session.toString()), payload.capture());
            var schema =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                            .getSchema(
                                    SchemaLocation.of(
                                            "classpath:schemas/certificate-"
                                                    + (result ? "result" : "command")
                                                    + "-v1.schema.json"));
            assertThat(
                            schema.validate(
                                    payload.getValue(),
                                    InputFormat.JSON,
                                    context ->
                                            context.executionConfig(
                                                    config ->
                                                            config.formatAssertionsEnabled(true))))
                    .isEmpty();
            reset(sender);
        }
    }
}
