package com.amateur.howmuchbackend.adapter.out.kafka;

import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncCommandOutboxPort;
import com.amateur.howmuchbackend.infra.account.config.AccountTransactionSyncProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AccountTransactionSyncKafkaPublisherIntegrationTest {
    private static final String TOPIC = "finance.sync.command.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

    @Test
    void publishesRc2CommandWithAccountRefKey() {
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        ));
        var publisher = new AccountTransactionSyncKafkaPublisher(
                new KafkaTemplate<>(producerFactory),
                new ObjectMapper(),
                properties()
        );
        UUID outboxId = UUID.randomUUID();
        UUID syncJobId = UUID.randomUUID();
        UUID accountRef = UUID.randomUUID();
        UUID connectionRef = UUID.randomUUID();

        try {
            publisher.publish(new AccountTransactionSyncCommandOutboxPort.PendingCommand(
                    outboxId,
                    1,
                    LocalDateTime.of(2026, 9, 9, 3, 0),
                    syncJobId,
                    accountRef,
                    2,
                    3,
                    connectionRef,
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 31),
                    LocalDateTime.of(2026, 9, 9, 3, 10)
            ));

            try (var consumer = consumer()) {
                consumer.subscribe(List.of(TOPIC));
                var records = consumer.poll(Duration.ofSeconds(10));
                assertThat(records).singleElement().satisfies(record -> {
                    assertThat(record.key()).isEqualTo(accountRef.toString());
                    assertThat(record.value())
                            .contains("\"messageId\":\"" + outboxId + "\"")
                            .contains("\"messageType\":\"SyncAccountTransactions\"")
                            .contains("\"syncJobId\":\"" + syncJobId + "\"")
                            .contains("\"connectionRef\":\"" + connectionRef + "\"")
                            .contains("\"timeZone\":\"Asia/Seoul\"")
                            .contains("\"reason\":\"USER_REQUEST\"");
                });
            }
        } finally {
            producerFactory.destroy();
        }
    }

    private KafkaConsumer<String, String> consumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "backend-sync-command-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(properties);
    }

    private AccountTransactionSyncProperties properties() {
        return new AccountTransactionSyncProperties(
                Set.of(),
                new AccountTransactionSyncProperties.Connector(
                        "http://127.0.0.1:8081", "", Duration.ofSeconds(1), Duration.ofSeconds(1)
                ),
                new AccountTransactionSyncProperties.Outbox(
                        false, TOPIC, Duration.ofMillis(200), Duration.ofSeconds(1), 10
                )
        );
    }
}
