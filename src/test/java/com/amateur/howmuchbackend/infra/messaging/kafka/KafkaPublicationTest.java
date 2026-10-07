package com.amateur.howmuchbackend.infra.messaging.kafka;

import com.amateur.howmuchbackend.adapter.out.kafka.notification.NotificationDeliveryKafkaPublisher;
import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort.DeliveryMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaPublicationTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void publisherSendsOnlyVersionedReferenceAndWaitsForAck() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var publisher = new NotificationDeliveryKafkaPublisher(new AcknowledgedKafkaSender(template, Duration.ofMillis(50)),
                json, properties());
        var message = new DeliveryMessage(UUID.randomUUID(), UUID.randomUUID(), 3, 42L,
                LocalDateTime.of(2026, 10, 7, 0, 0));
        publisher.publish(message);
        var value = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("notification.delivery.v1"), eq("42"), value.capture());
        var body = json.readTree(value.getValue());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("messageId", "messageType", "schemaVersion",
                "occurredAt", "deliveryId", "generation", "recipientUserId");
        assertThat(body.path("generation").longValue()).isEqualTo(3);
        assertThat(body.path("occurredAt").stringValue()).isEqualTo("2026-10-07T00:00:00Z");
    }

    @Test
    void missingOrFailedAckThrowsSoCallerCannotMarkPublished() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        var sender = new AcknowledgedKafkaSender(template, Duration.ofMillis(10));
        assertThatThrownBy(() -> sender.send("topic", "key", "value")).isInstanceOf(KafkaPublicationException.class);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("must-not-log")));
        assertThatThrownBy(() -> sender.send("topic", "key", "value"))
                .isInstanceOf(KafkaPublicationException.class).hasNoCause().hasMessageNotContaining("must-not-log");
    }

    @Test
    void recordsAckDeadlineMetricsWithoutMessageOrUserTags() {
        var meters = new SimpleMeterRegistry();
        var sender = new AcknowledgedKafkaSender(template, Duration.ofMillis(10), meters);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        sender.send("notification.delivery.v1", "42", "body");
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> sender.send("notification.delivery.v1", "42", "body"))
                .isInstanceOf(KafkaPublicationException.class);
        assertThat(meters.get("howmuch.messaging.kafka.publish").tag("result", "acknowledged").timer().count()).isEqualTo(1);
        assertThat(meters.get("howmuch.messaging.kafka.publish").tag("result", "failure").timer().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).extracting("key")
                        .containsExactlyInAnyOrder("destination", "result"));
    }

    @Test
    void interruptedAckPreservesThreadInterrupt() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new AcknowledgedKafkaSender(template, Duration.ofMillis(10))
                    .send("topic", "key", "value")).isInstanceOf(KafkaPublicationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void quarantineCopiesNoRawPayloadKeyHeaderOrExceptionAndRequiresAck() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var recoverer = new SanitizedKafkaDltRecoverer(new AcknowledgedKafkaSender(template, Duration.ofMillis(20)),
                json, properties(), Clock.fixed(java.time.Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
        var source = new ConsumerRecord<>("notification.delivery.v1", 0, 19, "secret-key", "secret-body");
        recoverer.accept(source, new InvalidKafkaMessageException());
        var value = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(template).send(eq("notification.delivery.v1.dlt"), eq("notification.delivery.v1:0:19"), value.capture());
        assertThat(value.getValue()).doesNotContain("secret-key", "secret-body", "stack", "exception");
        assertThat(json.readTree(value.getValue()).path("payloadSha256").stringValue()).matches("[0-9a-f]{64}");
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<SendResult<String, String>>());
        assertThatThrownBy(() -> recoverer.accept(source, new InvalidKafkaMessageException()))
                .isInstanceOf(KafkaPublicationException.class);
    }

    static KafkaMessagingProperties properties() {
        return new KafkaMessagingProperties(true, null, null, 1, null, null, null, false, null, null, null, false);
    }
}
