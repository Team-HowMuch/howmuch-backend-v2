package com.amateur.howmuchbackend.adapter.in.kafka.recap;

import com.amateur.howmuchbackend.adapter.in.kafka.KafkaMessageReader;
import com.amateur.howmuchbackend.application.port.in.notification.RecordRecapPublishedNotificationUseCase;
import com.amateur.howmuchbackend.application.port.in.notification.command.RecapPublishedCommand;
import com.amateur.howmuchbackend.infra.messaging.kafka.KafkaProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.YearMonth;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "howmuch.messaging.kafka", name = "enabled", havingValue = "true")
public class RecapPublishedKafkaListener {
    private static final Set<String> FIELDS = Set.of("messageId", "messageType", "schemaVersion", "occurredAt",
            "userId", "yearMonth", "completedSettlementCount");
    private final ObjectMapper json;
    private final RecordRecapPublishedNotificationUseCase useCase;

    public RecapPublishedKafkaListener(ObjectMapper json, RecordRecapPublishedNotificationUseCase useCase) {
        this.json = json;
        this.useCase = useCase;
    }

    @KafkaListener(topics = "${howmuch.messaging.kafka.recap-published-topic}",
            groupId = "${howmuch.messaging.kafka.recap-published-group}",
            autoStartup = "${howmuch.messaging.kafka.recap-consumer-enabled:false}",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void record(ConsumerRecord<String, String> record) {
        var body = KafkaMessageReader.read(json, record.value(), FIELDS, "RecapPublished");
        long userId = KafkaMessageReader.number(body, "userId", 1);
        if (!Long.toString(userId).equals(record.key())) throw KafkaMessageReader.invalid();
        String monthValue = KafkaMessageReader.text(body, "yearMonth");
        YearMonth month;
        try {
            if (!monthValue.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw KafkaMessageReader.invalid();
            month = YearMonth.parse(monthValue);
            if (month.getYear() < 1) throw KafkaMessageReader.invalid();
        } catch (RuntimeException exception) {
            throw KafkaMessageReader.invalid();
        }
        long count = KafkaMessageReader.number(body, "completedSettlementCount", 0);
        if (count > Integer.MAX_VALUE) throw KafkaMessageReader.invalid();
        var command = new RecapPublishedCommand(KafkaMessageReader.uuid(body, "messageId"), userId,
                month, (int) count, KafkaMessageReader.utcTime(body, "occurredAt"));
        try {
            useCase.record(command);
        } catch (IllegalArgumentException exception) {
            throw KafkaMessageReader.invalid();
        } catch (RuntimeException exception) {
            throw new KafkaProcessingException();
        }
    }
}
