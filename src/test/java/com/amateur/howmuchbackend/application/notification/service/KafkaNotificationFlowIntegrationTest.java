package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.messaging.kafka.AcknowledgedKafkaSender;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"howmuch.messaging.kafka.enabled=true", "howmuch.notification.delivery-mode=KAFKA",
        "howmuch.messaging.kafka.notification-consumer-enabled=true", "howmuch.messaging.kafka.recap-consumer-enabled=true",
        "howmuch.settlement.notification-outbox.enabled=false", "howmuch.settlement.automatic-reminder.enabled=false"})
class KafkaNotificationFlowIntegrationTest {
    private static final String SUFFIX = UUID.randomUUID().toString();
    private static final String DELIVERY = "notification.delivery.v1.test." + SUFFIX;
    private static final String RECAP = "recap.published.v1.test." + SUFFIX;
    @Container static final KafkaContainer BROKER = new KafkaContainer("apache/kafka:4.2.2")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    @DynamicPropertySource static void brokerProperties(DynamicPropertyRegistry properties) throws Exception {
        BROKER.start();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", BROKER.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(DELIVERY,1,(short)1),new NewTopic(DELIVERY+".dlt",1,(short)1),
                    new NewTopic(RECAP,1,(short)1),new NewTopic(RECAP+".dlt",1,(short)1))).all().get();
        }
        properties.add("spring.kafka.bootstrap-servers", BROKER::getBootstrapServers);
        properties.add("howmuch.messaging.kafka.notification-delivery-topic", () -> DELIVERY);
        properties.add("howmuch.messaging.kafka.notification-delivery-dlt-topic", () -> DELIVERY+".dlt");
        properties.add("howmuch.messaging.kafka.notification-delivery-group", () -> "delivery-flow-"+SUFFIX);
        properties.add("howmuch.messaging.kafka.recap-published-topic", () -> RECAP);
        properties.add("howmuch.messaging.kafka.recap-published-dlt-topic", () -> RECAP+".dlt");
        properties.add("howmuch.messaging.kafka.recap-published-group", () -> "recap-flow-"+SUFFIX);
    }
    @Autowired SaveUserPort users;
    @Autowired JdbcTemplate jdbc;
    @Autowired AcknowledgedKafkaSender kafka;
    @Autowired NotificationDeliveryRelay relay;
    @Autowired NotificationDeliveryPublisherPort publisher;
    @Autowired ObjectMapper json;
    @MockitoBean SettlementPushSenderPort sender;
    private Long recipientId;
    private final AtomicInteger sent = new AtomicInteger();
    @AfterEach void cleanUp() {
        if (recipientId != null) {
            jdbc.update("DELETE FROM settlement_notification_outbox WHERE recipient_user_id=?",recipientId);
            jdbc.update("DELETE FROM user_notifications WHERE recipient_user_id=?",recipientId);
            jdbc.update("DELETE FROM users WHERE id=?",recipientId);
        }
    }
    @Test void actualRecapBrokerToInboxToRelayToProvider_isDurableAndDeduplicated() throws Exception {
        String unique=UUID.randomUUID().toString().replace("-","");
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        recipientId=users.save(User.register("kafka-flow-"+unique,"수신자",LocalDate.of(1995,1,1),
                "k"+unique.substring(0,12),"test-"+unique.substring(0,12),"unused",now,false)).id();
        jdbc.update("UPDATE users SET fcm_token='synthetic-test-only-token' WHERE id=?",recipientId);
        when(sender.sendNotification(anyString(),any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var message=invocation.getArgument(1,SettlementPushSenderPort.NotificationMessage.class);
            assertThat(message.type()).isEqualTo("MONTHLY_RECAP");
            assertThat(message.settlementId()).isNull();
            sent.incrementAndGet(); return SettlementPushSenderPort.SendResult.SENT;
        });
        var month=YearMonth.from(Instant.now().atZone(ZoneId.of("Asia/Seoul"))).minusMonths(1);
        kafka.send(RECAP,recipientId.toString(),recap(month,now));
        await(() -> countNotifications()==1);
        kafka.send(RECAP,recipientId.toString(),recap(month,now));
        // A subsequent zero-count message fences the earlier duplicate on this partition.
        var fence=json.readTree(recap(month.minusMonths(1),now));
        ((tools.jackson.databind.node.ObjectNode)fence).put("completedSettlementCount",0);
        kafka.send(RECAP,recipientId.toString(),json.writeValueAsString(fence));
        await(() -> jdbc.queryForObject("SELECT count(*) FROM settlement_notification_outbox WHERE recipient_user_id=?",Integer.class,recipientId)==1);
        var original=jdbc.queryForObject("SELECT id,delivery_message_id,delivery_generation,created_at FROM settlement_notification_outbox WHERE recipient_user_id=?",
                (rs,row) -> new NotificationDeliveryPublisherPort.DeliveryMessage(rs.getObject("delivery_message_id",UUID.class),
                        rs.getObject("id",UUID.class),rs.getLong("delivery_generation"),recipientId,rs.getObject("created_at",LocalDateTime.class)),recipientId);
        assertThat(original.generation()).isZero();
        publisher.publish(new NotificationDeliveryPublisherPort.DeliveryMessage(UUID.randomUUID(),
                original.deliveryId(), original.generation(), original.recipientUserId(), original.occurredAt()));
        await(() -> committedOffsetAtLeast(DELIVERY,"delivery-flow-"+SUFFIX,1));
        assertThat(sent).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM settlement_notification_outbox WHERE id=?",Integer.class,original.deliveryId())).isZero();
        relay.relayBatch(5);
        await(() -> "SUCCEEDED".equals(jdbc.queryForObject("SELECT status FROM settlement_notification_outbox WHERE id=?",String.class,original.deliveryId())));
        publisher.publish(original); // Replay the same durable generation after completion.
        await(() -> committedOffsetAtLeast(DELIVERY,"delivery-flow-"+SUFFIX,3));
        await(() -> committedOffsetAtLeast(RECAP,"recap-flow-"+SUFFIX,3));
        assertThat(sent).hasValue(1);
        assertThat(countNotifications()).isOne();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM settlement_notification_outbox WHERE id=?",Integer.class,original.deliveryId())).isOne();
    }
    private String recap(YearMonth month,LocalDateTime now) {
        var body=json.createObjectNode();body.put("messageId",UUID.randomUUID().toString());body.put("messageType","RecapPublished");
        body.put("schemaVersion",1);body.put("occurredAt",now.toInstant(ZoneOffset.UTC).toString());body.put("userId",recipientId);
        body.put("yearMonth",month.toString());body.put("completedSettlementCount",1);return json.writeValueAsString(body);
    }
    private int countNotifications() {return jdbc.queryForObject("SELECT count(*) FROM user_notifications WHERE recipient_user_id=?",Integer.class,recipientId);}
    private boolean committedOffsetAtLeast(String topic,String group,long expected) {
        try (var admin=AdminClient.create(Map.of("bootstrap.servers",BROKER.getBootstrapServers()))) {
            return admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get().entrySet().stream()
                    .anyMatch(entry -> entry.getKey().topic().equals(topic) && entry.getValue().offset()>=expected);
        } catch (Exception exception) {return false;}
    }
    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline) {if(condition.getAsBoolean()) return;Thread.sleep(50);}
        fail("Expected durable Kafka notification state did not become visible.");
    }
}
