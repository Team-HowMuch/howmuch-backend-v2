package com.amateur.howmuchbackend.adapter.in.kafka;

import com.amateur.howmuchbackend.adapter.in.kafka.notification.NotificationDeliveryKafkaListener;
import com.amateur.howmuchbackend.adapter.in.kafka.recap.RecapPublishedKafkaListener;
import com.amateur.howmuchbackend.application.port.in.notification.DeliverNotificationUseCase;
import com.amateur.howmuchbackend.application.port.in.notification.RecordRecapPublishedNotificationUseCase;
import com.amateur.howmuchbackend.application.port.in.notification.command.RecapPublishedCommand;
import com.amateur.howmuchbackend.application.port.in.notification.command.DeliverNotificationCommand;
import com.amateur.howmuchbackend.infra.messaging.kafka.InvalidKafkaMessageException;
import com.amateur.howmuchbackend.infra.messaging.kafka.KafkaProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KafkaMessageContractTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final DeliverNotificationUseCase delivery = mock(DeliverNotificationUseCase.class);
    private final NotificationDeliveryKafkaListener listener = new NotificationDeliveryKafkaListener(json, delivery);
    private final UUID messageId = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();

    String payload() {
        return """
                {"messageId":"%s","messageType":"NotificationDeliveryRequested","schemaVersion":1,
                "occurredAt":"2026-10-07T00:00:00Z","deliveryId":"%s","generation":1,"recipientUserId":42}
                """.formatted(messageId, deliveryId);
    }

    @Test
    void delivery_mapsOnlyDurableReferenceAndGeneration() {
        listener.deliver(record("42", payload()));
        verify(delivery).deliver(new DeliverNotificationCommand(messageId, deliveryId, 1, 42L,
                LocalDateTime.of(2026, 10, 7, 0, 0)));
        listener.deliver(record("42", payload().replace("generation\":1", "generation\":0")));
        verify(delivery).deliver(new DeliverNotificationCommand(messageId, deliveryId, 0, 42L,
                LocalDateTime.of(2026, 10, 7, 0, 0)));
    }

    @Test
    void delivery_rejectsWrongKeyIdsSchemaUnknownFieldsAndInvalidJson() {
        assertRejected("41", payload());
        assertRejected("42", payload().replace(deliveryId.toString(), "1-1-1-1-1"));
        assertRejected("42", payload().replace("schemaVersion\":1", "schemaVersion\":2"));
        assertRejected("42", payload().replace("generation\":1", "generation\":1.0"));
        assertRejected("42", payload().replace("generation\":1", "generation\":-1"));
        assertRejected("42", payload().replace("recipientUserId\":42", "recipientUserId\":9223372036854775808"));
        assertRejected("42", payload().replace("00:00:00Z", "09:00:00+09:00"));
        assertRejected("42", payload().replace("\"generation\":1", "\"generation\":1,\"pushToken\":\"secret\""));
        assertRejected("42", payload().replace("\"generation\":1", "\"generation\":1,\"generation\":2"));
        assertRejected("42", payload() + "{}");
        assertRejected("42", "x".repeat(4097));
        assertRejected("42", null);
        verifyNoInteractions(delivery);
    }

    @Test
    void localFailureRemainsRetryableAndDoesNotLeakItsBody() {
        doThrow(new IllegalStateException("must-not-log-provider-secret")).when(delivery).deliver(any(DeliverNotificationCommand.class));
        assertThatThrownBy(() -> listener.deliver(record("42", payload())))
                .isInstanceOf(KafkaProcessingException.class)
                .hasNoCause().hasMessageNotContaining("must-not-log");
    }

    @Test
    void canonicalLedgerMismatchIsQuarantinedRatherThanRetried() {
        doThrow(new IllegalArgumentException("must-not-log-ledger-values"))
                .when(delivery).deliver(any(DeliverNotificationCommand.class));
        assertThatThrownBy(() -> listener.deliver(record("42", payload())))
                .isInstanceOf(InvalidKafkaMessageException.class).hasNoCause();
    }

    @Test
    void recap_mapsUtcPublicationAndRejectsWrongRouteOrMonth() {
        var useCase = mock(RecordRecapPublishedNotificationUseCase.class);
        var recap = new RecapPublishedKafkaListener(json, useCase);
        String value = """
                {"messageId":"%s","messageType":"RecapPublished","schemaVersion":1,
                "occurredAt":"2026-10-01T00:00:00Z","userId":42,"yearMonth":"2026-09","completedSettlementCount":2}
                """.formatted(messageId);
        recap.record(record("42", value));
        verify(useCase).record(new RecapPublishedCommand(messageId, 42L, YearMonth.of(2026, 9), 2,
                LocalDateTime.of(2026, 10, 1, 0, 0)));
        assertThatThrownBy(() -> recap.record(record("41", value))).isInstanceOf(InvalidKafkaMessageException.class);
        assertThatThrownBy(() -> recap.record(record("42", value.replace("2026-09", "2026-13"))))
                .isInstanceOf(InvalidKafkaMessageException.class);
        assertThatThrownBy(() -> recap.record(record("42", value.replace("2026-09", "0000-01"))))
                .isInstanceOf(InvalidKafkaMessageException.class);
        assertThatThrownBy(() -> recap.record(record("42", value.replace("Count\":2", "Count\":2147483648"))))
                .isInstanceOf(InvalidKafkaMessageException.class);
        doThrow(new IllegalArgumentException("must-not-log-invalid-month")).when(useCase).record(any());
        assertThatThrownBy(() -> recap.record(record("42", value)))
                .isInstanceOf(InvalidKafkaMessageException.class).hasNoCause();
    }

    private void assertRejected(String key, String value) {
        assertThatThrownBy(() -> listener.deliver(record(key, value)))
                .isInstanceOf(InvalidKafkaMessageException.class).hasNoCause();
    }

    private ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("notification.delivery.v1", 0, 0, key, value);
    }
}
