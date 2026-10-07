package com.amateur.howmuchbackend.infra.messaging.kafka;

import com.amateur.howmuchbackend.adapter.in.kafka.notification.NotificationDeliveryKafkaListener;
import com.amateur.howmuchbackend.adapter.out.kafka.notification.NotificationDeliveryKafkaPublisher;
import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort.DeliveryMessage;
import com.networknt.schema.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class KafkaTransportIntegrationTest {
    @Container
    static final KafkaContainer BROKER = new KafkaContainer("apache/kafka:4.2.2")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    private final JsonMapper json = JsonMapper.builder().build();
    private AdminClient admin;
    private DefaultKafkaProducerFactory<String, String> producer;
    private KafkaTemplate<String, String> template;

    @BeforeEach
    void connect() {
        admin = AdminClient.create(Map.of("bootstrap.servers", BROKER.getBootstrapServers()));
        producer = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 600, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1200));
        template = new KafkaTemplate<>(producer);
    }

    @AfterEach
    void close() {
        template.destroy(); producer.destroy(); admin.close();
    }

    @Test
    void realBrokerAckRoundTripKeepsNotificationAndRc2FinanceWireContracts() throws Exception {
        var props = properties("roundtrip-" + UUID.randomUUID());
        provision(props.notificationDeliveryTopic(), "finance.sync.command.v1", "finance.sync.event.v1");
        var sender = new AcknowledgedKafkaSender(template, Duration.ofSeconds(3));
        var publisher = new NotificationDeliveryKafkaPublisher(sender, json, props);
        var message = new DeliveryMessage(UUID.randomUUID(), UUID.randomUUID(), 0, 42L,
                LocalDateTime.of(2026, 10, 7, 0, 0));
        publisher.publish(message);
        try (var consumer = consumer(UUID.randomUUID().toString(), props.notificationDeliveryTopic())) {
            var received = receive(consumer);
            assertThat(received.key()).isEqualTo("42");
            validate("notification-delivery-v1.schema.json", received.value());
        }
        for (String fixture : List.of("finance-sync-account-transactions-v1.json", "finance-transactions-empty-v1.json")) {
            String value = resource("messaging/examples/" + fixture);
            validate("finance-sync-message-v1.schema.json", value);
            String topic = fixture.contains("account-transactions") ? "finance.sync.command.v1" : "finance.sync.event.v1";
            String key = json.readTree(value).path("accountRef").stringValue();
            sender.send(topic, key, value);
            try (var consumer = consumer(UUID.randomUUID().toString(), topic)) {
                var received = receive(consumer);
                assertThat(received.key()).isEqualTo(key);
                assertThat(received.value()).isEqualTo(value);
            }
        }
    }

    @Test
    void invalidRecordOffsetWaitsForDurableSanitizedDltAck() throws Exception {
        var props = properties("quarantine-" + UUID.randomUUID());
        provision(props.notificationDeliveryTopic()); // DLT intentionally absent; broker auto-create is disabled.
        var useCaseCalls = new AtomicInteger();
        var listener = new NotificationDeliveryKafkaListener(json, (id, generation) -> useCaseCalls.incrementAndGet());
        var container = container(props, listener);
        container.start();
        try {
            new AcknowledgedKafkaSender(template, Duration.ofSeconds(3)).send(props.notificationDeliveryTopic(),
                    "must-not-copy-key", "{\"pushToken\":\"must-not-copy-body\"}");
            Thread.sleep(1700);
            assertThat(committed(props)).isLessThan(1);
            assertThat(useCaseCalls).hasValue(0);
            provision(props.notificationDeliveryDltTopic());
            try (var consumer = consumer(UUID.randomUUID().toString(), props.notificationDeliveryDltTopic())) {
                String quarantined = receive(consumer).value();
                assertThat(quarantined).doesNotContain("must-not-copy", "pushToken", "exception");
                validate("kafka-message-quarantined-v1.schema.json", quarantined);
            }
            await(() -> committed(props) == 1);
        } finally {
            container.stop();
        }
    }

    @Test
    void localDbFailureRedeliversAndCommitsOnlyAfterUseCaseReturns() throws Exception {
        var props = properties("db-retry-" + UUID.randomUUID());
        provision(props.notificationDeliveryTopic(), props.notificationDeliveryDltTopic());
        var recovered = new AtomicBoolean(false);
        var calls = new AtomicInteger();
        var commits = new AtomicInteger();
        var listener = new NotificationDeliveryKafkaListener(json, (id, generation) -> {
            calls.incrementAndGet();
            if (!recovered.get()) throw new org.springframework.dao.DataAccessResourceFailureException("database down");
            commits.incrementAndGet();
        });
        var container = container(props, listener);
        container.start();
        try {
            new NotificationDeliveryKafkaPublisher(new AcknowledgedKafkaSender(template, Duration.ofSeconds(3)), json, props)
                    .publish(new DeliveryMessage(UUID.randomUUID(), UUID.randomUUID(), 1, 42L, LocalDateTime.now(ZoneOffset.UTC)));
            await(() -> calls.get() >= 2);
            assertThat(committed(props)).isLessThan(1);
            assertThat(commits).hasValue(0);
            recovered.set(true);
            await(() -> committed(props) == 1);
            assertThat(commits).hasValue(1);
            assertThat(admin.listOffsets(Map.of(new TopicPartition(props.notificationDeliveryDltTopic(), 0),
                    org.apache.kafka.clients.admin.OffsetSpec.latest())).all().get()
                    .get(new TopicPartition(props.notificationDeliveryDltTopic(), 0)).offset()).isZero();
        } finally {
            container.stop();
        }
    }

    @Test
    void unprovisionedTopicCannotProduceAcknowledgedSuccess() {
        assertThatThrownBy(() -> new AcknowledgedKafkaSender(template, Duration.ofMillis(100))
                .send("missing-" + UUID.randomUUID(), "key", "synthetic"))
                .isInstanceOf(KafkaPublicationException.class);
    }

    private org.springframework.kafka.listener.ConcurrentMessageListenerContainer<String, String> container(
            KafkaMessagingProperties props, NotificationDeliveryKafkaListener listener) {
        KafkaProperties kafka = new KafkaProperties();
        kafka.setBootstrapServers(List.of(BROKER.getBootstrapServers()));
        kafka.getConsumer().setKeyDeserializer(StringDeserializer.class);
        kafka.getConsumer().setValueDeserializer(StringDeserializer.class);
        kafka.getConsumer().setAutoOffsetReset("earliest");
        kafka.getConsumer().setMaxPollRecords(1);
        var config = new KafkaMessagingConfig();
        var handler = config.kafkaContractErrorHandler(new AcknowledgedKafkaSender(template, Duration.ofMillis(700)),
                json, props, Clock.systemUTC());
        var factory = config.durableKafkaListenerContainerFactory(kafka, props, handler, null);
        var result = factory.createContainer(props.notificationDeliveryTopic());
        result.getContainerProperties().setGroupId(props.notificationDeliveryGroup());
        result.getContainerProperties().setMessageListener((MessageListener<String, String>) listener::deliver);
        return result;
    }

    private KafkaMessagingProperties properties(String prefix) {
        return new KafkaMessagingProperties(true, Duration.ofSeconds(3), Duration.ofMillis(100), 1,
                prefix, prefix + ".dlt", prefix + "-group", true,
                "recap.published.v1", "recap.published.v1.dlt", "recap-group", false);
    }

    private void provision(String... topics) throws Exception {
        admin.createTopics(Arrays.stream(topics).map(t -> new NewTopic(t, 1, (short) 1)).toList()).all().get();
    }

    private KafkaConsumer<String, String> consumer(String group, String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", BROKER.getBootstrapServers(),
                "group.id", group, "auto.offset.reset", "earliest", "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private ConsumerRecord<String, String> receive(KafkaConsumer<String, String> consumer) {
        Instant until = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(until)) {
            var values = consumer.poll(Duration.ofMillis(100));
            if (!values.isEmpty()) return values.iterator().next();
        }
        throw new AssertionError("Expected synthetic Kafka record was not received.");
    }

    private long committed(KafkaMessagingProperties props) {
        try {
            var offset = admin.listConsumerGroupOffsets(props.notificationDeliveryGroup()).partitionsToOffsetAndMetadata()
                    .get().get(new TopicPartition(props.notificationDeliveryTopic(), 0));
            return offset == null ? -1 : offset.offset();
        } catch (Exception exception) {
            return -1;
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        Instant until = Instant.now().plusSeconds(15);
        while (!condition.getAsBoolean() && Instant.now().isBefore(until)) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void validate(String file, String payload) {
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(SchemaLocation.of("classpath:schemas/" + file));
        assertThat(schema.validate(payload, InputFormat.JSON,
                context -> context.executionConfig(c -> c.formatAssertionsEnabled(true)))).isEmpty();
    }

    private String resource(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("Missing fixture");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
