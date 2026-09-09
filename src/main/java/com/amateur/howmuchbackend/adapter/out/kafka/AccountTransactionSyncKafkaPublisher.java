package com.amateur.howmuchbackend.adapter.out.kafka;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandOutboxPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandPublisherPort;
import com.amateur.howmuchbackend.infra.account.config.AccountTransactionSyncProperties;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;

@Component
public class AccountTransactionSyncKafkaPublisher implements AccountTransactionSyncCommandPublisherPort {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AccountTransactionSyncProperties properties;
    private final Schema schema = SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("classpath:schemas/finance-sync-message-v1.schema.json"));

    public AccountTransactionSyncKafkaPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            AccountTransactionSyncProperties properties
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void publish(AccountTransactionSyncCommandOutboxPort.PendingCommand command) {
        String payload = payload(command);
        var errors = schema.validate(payload, InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)));
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "RC2 금융 동기화 command 계약을 위반했습니다: "
                            + errors.iterator().next().getMessage()
            );
        }
        try {
            kafkaTemplate.send(
                    properties.outbox().commandTopic(),
                    command.accountRef().toString(),
                    payload
            ).get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("금융 동기화 command 발행이 중단되었습니다.", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("금융 동기화 command 발행에 실패했습니다.", exception.getCause());
        }
    }

    private String payload(AccountTransactionSyncCommandOutboxPort.PendingCommand command) {
        var root = objectMapper.createObjectNode();
        root.put("messageId", command.outboxId().toString());
        root.put("messageType", "SyncAccountTransactions");
        root.put("schemaVersion", 1);
        root.put("occurredAt", command.occurredAt().toInstant(ZoneOffset.UTC).toString());
        root.put("syncJobId", command.syncJobId().toString());
        root.put("accountRef", command.accountRef().toString());
        root.put("syncVersion", command.syncVersion());
        root.put("connectionVersion", command.connectionVersion());
        root.putNull("causationId");
        root.putNull("traceId");
        var data = root.putObject("data");
        data.put("connectionRef", command.connectionRef().toString());
        data.put("fromDate", command.fromDate().toString());
        data.put("toDate", command.toDate().toString());
        data.put("timeZone", "Asia/Seoul");
        data.put("reason", "USER_REQUEST");
        data.put("deadlineAt", command.deadlineAt().toInstant(ZoneOffset.UTC).toString());
        return objectMapper.writeValueAsString(root);
    }
}
