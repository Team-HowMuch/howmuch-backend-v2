package com.amateur.howmuchbackend.application.recap.service;

import com.amateur.howmuchbackend.adapter.in.kafka.recap.RecapPublishedKafkaListener;
import com.amateur.howmuchbackend.adapter.out.kafka.recap.RecapKafkaPublisher;
import com.amateur.howmuchbackend.application.port.in.notification.RecordRecapPublishedNotificationUseCase;
import com.amateur.howmuchbackend.application.port.out.recap.*;
import com.amateur.howmuchbackend.application.recap.model.*;
import com.amateur.howmuchbackend.application.recap.policy.RecapPublicationRetryPolicy;
import com.amateur.howmuchbackend.infra.messaging.kafka.*;
import com.amateur.howmuchbackend.support.SettlementUnpaidFixtures;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class RecapPublicationIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RecapSnapshotService snapshots;
    @Autowired private StoreMonthlyRecapPort snapshotStorage;
    @Autowired private RecapPublicationStorePort store;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private RecordRecapPublishedNotificationUseCase notification;
    private SettlementUnpaidFixtures fixtures;
    private long owner;
    private static final YearMonth MONTH = YearMonth.of(2026, 9);

    @BeforeEach void setup() { fixtures = new SettlementUnpaidFixtures(jdbc); owner = fixtures.user(); }
    @AfterEach void cleanup() { fixtures.close(); }

    @Test
    void enqueueFailureRollsBackTheSnapshotAndAnOuterRollbackRollsBackBoth() {
        var recap = recap();
        jdbc.execute("ALTER TABLE recap_publication_outbox ADD CONSTRAINT ck_test_reject_enqueue CHECK (recap_id <> '" + recap.id() + "')");
        try {
            assertThatThrownBy(() -> snapshots.store(recap)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("ALTER TABLE recap_publication_outbox DROP CONSTRAINT ck_test_reject_enqueue");
        }
        assertThat(count("monthly_recaps", "id", recap.id())).isZero();
        assertThat(count("recap_publication_outbox", "recap_id", recap.id())).isZero();
        var another = recap();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(snapshots.store(another)).isTrue();
            tx.setRollbackOnly();
        });
        assertThat(count("monthly_recaps", "id", another.id())).isZero();
        assertThat(count("recap_publication_outbox", "recap_id", another.id())).isZero();
    }

    @Test
    void duplicateSnapshotDoesNotCreateOrResetAPublication() {
        var recap = recap();
        assertThat(snapshots.store(recap)).isTrue();
        var claim = store.claimNext(now(), now().plusMinutes(2)).orElseThrow();
        assertThat(store.published(claim, now())).isTrue();
        assertThat(snapshots.store(recap())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recap_publication_outbox", Integer.class)).isOne();
        assertThat(status(recap)).isEqualTo("PUBLISHED");
    }

    @Test
    void legacySnapshotsAreNotBackfilledByRepeatedStorage() {
        var recap = recap();
        assertThat(snapshotStorage.insertIfAbsent(recap)).isTrue();
        assertThat(snapshots.store(recap)).isFalse();
        assertThat(count("recap_publication_outbox", "recap_id", recap.id())).isZero();
    }

    @Test
    void expiryReusesTheImmutableMessageAndFencesStaleOwners() {
        var recap = recap(); snapshots.store(recap);
        var time = now();
        var first = store.claimNext(time, time.plusMinutes(2)).orElseThrow();
        assertThat(store.recoverExpired(time.plusMinutes(3), 100)).isOne();
        var second = store.claimNext(time.plusMinutes(3), time.plusMinutes(5)).orElseThrow();
        assertThat(second.message()).isEqualTo(first.message());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(store.published(first, time.plusMinutes(3))).isFalse();
        assertThat(store.retry(first, time.plusMinutes(3), time.plusMinutes(4))).isFalse();
        assertThat(store.published(second, time.plusMinutes(3))).isTrue();
    }

    @Test
    void onlyOneConcurrentWorkerClaimsTheSameEvent() throws Exception {
        snapshots.store(recap());
        var time = now();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> task = () -> { start.await(); return store.claimNext(time, time.plusMinutes(2)).isPresent(); };
            var a = workers.submit(task); var b = workers.submit(task); start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void failedAckWaitsBeforeRetryingTheSameMessage() {
        var recap = recap(); snapshots.store(recap);
        var publisher = mock(RecapEventPublisherPort.class);
        doThrow(new KafkaPublicationException()).doNothing().when(publisher).publish(any());
        var clock = Clock.fixed(Instant.now().plusSeconds(1), ZoneOffset.UTC);
        var relay = new RecapPublicationRelay(store, publisher, new RecapPublicationRetryPolicy(), clock);
        assertThat(relay.publishBatch(100)).isZero();
        assertThat(status(recap)).isEqualTo("PENDING");
        assertThat(relay.publishBatch(100)).isZero();
        var later = new RecapPublicationRelay(store, publisher, new RecapPublicationRetryPolicy(),
                Clock.offset(clock, Duration.ofSeconds(11)));
        assertThat(later.publishBatch(100)).isOne();
        var messages = ArgumentCaptor.forClass(RecapPublicationMessage.class);
        verify(publisher, times(2)).publish(messages.capture());
        assertThat(messages.getAllValues().get(0)).isEqualTo(messages.getAllValues().get(1));
        assertThat(status(recap)).isEqualTo("PUBLISHED");
    }

    @Test
    void databaseRejectsIncompleteStatesAndDeletingTheSnapshotCancelsPublication() {
        var recap = recap(); snapshots.store(recap);
        for (String invalid : List.of("status='UNKNOWN'", "attempt_count=-1", "next_attempt_at=NULL",
                "status='PROCESSING',attempt_count=1,next_attempt_at=NULL", "status='PUBLISHED',attempt_count=1,next_attempt_at=NULL")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE recap_publication_outbox SET " + invalid + " WHERE recap_id=?", recap.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        var claim = store.claimNext(now(), now().plusMinutes(2)).orElseThrow();
        jdbc.update("DELETE FROM monthly_recaps WHERE id=?", recap.id());
        assertThat(count("recap_publication_outbox", "recap_id", recap.id())).isZero();
        assertThat(store.published(claim, now())).isFalse();
    }

    @Test
    void inactiveOwnerIsNotPublished() {
        var recap = recap(); snapshots.store(recap);
        jdbc.update("UPDATE users SET active=FALSE WHERE id=?", owner);
        assertThat(store.claimNext(now(), now().plusMinutes(2))).isEmpty();
    }

    @Test
    void realKafkaRedeliveryAfterCrashCreatesOneInboxAndOnePushIntent() {
        var recap = recap(); snapshots.store(recap);
        String topic = "recap-publication-" + UUID.randomUUID();
        var broker = new EmbeddedKafkaKraftBroker(1, 1, topic).brokerProperties(Map.of(
                "auto.create.topics.enable", "false", "log.segment.bytes", "1048576",
                "num.network.threads", "2", "num.io.threads", "2"));
        broker.afterPropertiesSet();
        var producer = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
        var template = new KafkaTemplate<>(producer);
        var json = JsonMapper.builder().build();
        var properties = new KafkaMessagingProperties(true, null, null, 1, null, null, null, false,
                topic, topic + ".dlt", "recap-test", false);
        var publisher = new RecapKafkaPublisher(new AcknowledgedKafkaSender(template, Duration.ofSeconds(5)), json, properties);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest", ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.subscribe(List.of(topic));
            var time = now();
            var first = store.claimNext(time, time.plusMinutes(2)).orElseThrow();
            publisher.publish(first.message());
            // Simulate a process exit after Kafka ACK and before recording PUBLISHED.
            assertThat(store.recoverExpired(time.plusMinutes(3), 100)).isOne();
            var relay = new RecapPublicationRelay(store, publisher, new RecapPublicationRetryPolicy(),
                    Clock.fixed(time.plusMinutes(3).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
            assertThat(relay.publishBatch(100)).isOne();
            var records = new ArrayList<ConsumerRecord<String, String>>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (records.size() < 2 && System.nanoTime() < deadline) consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            assertThat(records).hasSize(2);
            assertThat(records).extracting(ConsumerRecord::key).containsOnly(Long.toString(owner));
            assertThat(records.get(0).value()).isEqualTo(records.get(1).value());
            var body = json.readTree(records.get(0).value());
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("messageId", "messageType", "schemaVersion",
                    "occurredAt", "userId", "yearMonth", "completedSettlementCount");
            assertThat(body.path("messageId").stringValue()).isEqualTo(recap.id().toString());
            assertThat(body.path("occurredAt").stringValue()).isEqualTo("2026-10-01T00:00:00Z");
            var listener = new RecapPublishedKafkaListener(json, notification);
            records.forEach(listener::record);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE recipient_user_id=? AND notification_type='MONTHLY_RECAP' AND target_id='2026-09'", Integer.class, owner)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM settlement_notification_outbox WHERE recipient_user_id=?", Integer.class, owner)).isOne();
        } finally {
            template.destroy(); producer.destroy(); broker.destroy();
        }
    }

    private MonthlyRecap recap() {
        return new MonthlyRecap(UUID.randomUUID(), owner, MONTH, "집계 검증", new RecapSummary(1, 1200),
                new RecapComparison(RecapComparison.Status.NO_PREVIOUS, null, null, null), null,
                LocalDateTime.of(2026, 10, 1, 0, 0), null, null, null);
    }

    private int count(String table, String key, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + key + "=?", Integer.class, id);
    }

    private String status(MonthlyRecap recap) {
        return jdbc.queryForObject("SELECT status FROM recap_publication_outbox WHERE recap_id=?", String.class, recap.id());
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).plusSeconds(1); }
}
