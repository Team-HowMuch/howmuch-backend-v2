package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort;
import com.amateur.howmuchbackend.application.port.in.notification.command.DeliverNotificationCommand;
import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import com.amateur.howmuchbackend.application.settlement.service.SettlementNotificationOutboxWorker;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.notification.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false"
})
class NotificationDeliveryIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 0, 0);
    @Autowired NotificationRecorder recorder;
    @Autowired NotificationDeliveryStorePort store;
    @Autowired NotificationDeliveryService delivery;
    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementNotificationOutboxWorker directWorker;
    @MockitoBean Clock clock;
    @MockitoBean SettlementPushSenderPort sender;
    private final NotificationDeliveryPublisherPort publisher = mock(NotificationDeliveryPublisherPort.class);
    private Long recipient;
    private UUID notificationId, deliveryId;
    private UUID reminderSettlement;

    @BeforeEach void setUp() {
        time(NOW);
        String unique = UUID.randomUUID().toString();
        recipient = jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,fcm_token,created_at,updated_at)
                VALUES (?,'배달 테스트',?,?,'unused',true,'USER',true,'provider-token',?,?) RETURNING id
                """, Long.class, unique, "d_" + unique.replace("-", "").substring(0, 10), unique.substring(0, 20), NOW, NOW);
        notificationId = UUID.randomUUID();
        recorder.record(new UserNotification(notificationId, recipient, null, NotificationType.MONTHLY_RECAP,
                null, "HowMuch", "월간 리캡", "지난달 정산 내역을 확인해 주세요", "delivery-test-" + notificationId,
                null, NOW, new NotificationTarget(NotificationTargetType.MONTHLY_RECAP, "2026-09")));
        deliveryId = jdbc.queryForObject("SELECT id FROM settlement_notification_outbox WHERE notification_id=?", UUID.class, notificationId);
    }

    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM settlement_notification_outbox WHERE recipient_user_id=?", recipient);
        jdbc.update("DELETE FROM user_notifications WHERE recipient_user_id=?", recipient);
        if (reminderSettlement != null) jdbc.update("DELETE FROM settlements WHERE id=?", reminderSettlement);
        jdbc.update("DELETE FROM users WHERE id=?", recipient);
        reset(publisher);
    }

    @Test void replayAndConcurrentConsumers_sendOneOutsideTransaction() throws Exception {
        when(sender.sendNotification(anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return SettlementPushSenderPort.SendResult.SENT;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); delivery.deliver(deliveryId, 0); return true; });
            var b = executor.submit(() -> { start.await(); delivery.deliver(deliveryId, 0); return true; });
            start.countDown(); a.get(15, TimeUnit.SECONDS); b.get(15, TimeUnit.SECONDS);
        }
        delivery.deliver(deliveryId, 0);
        verify(sender, times(1)).sendNotification(eq("provider-token"), any());
        assertThat(providerStatus()).isEqualTo("SUCCEEDED");
        assertThat(providerAttempts()).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE id=?", Integer.class, notificationId)).isOne();
    }

    @Test void brokerFailure_keepsProviderBudgetAndMessageIdentity_thenEarlyConsumerBeforeAckIsSafe() {
        UUID originalMessage = messageId();
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(any());
        assertThat(relay().relayBatch(1).retried()).isOne();
        assertThat(providerStatus()).isEqualTo("PENDING");
        assertThat(providerAttempts()).isZero();
        assertThat(messageId()).isEqualTo(originalMessage);
        assertThat(publicationStatus()).isEqualTo("PENDING");
        assertThat(relay().relayBatch(1).claimed()).isZero();
        time(NOW.plusSeconds(6));
        doReturn(SettlementPushSenderPort.SendResult.SENT).when(sender).sendNotification(anyString(), any());
        doAnswer(call -> {
            NotificationDeliveryPublisherPort.DeliveryMessage m = call.getArgument(0);
            assertThat(m.messageId()).isEqualTo(originalMessage);
            delivery.deliver(m.deliveryId(), m.generation());
            assertThat(providerStatus()).isEqualTo("SUCCEEDED");
            return null;
        }).when(publisher).publish(any());
        assertThat(relay().relayBatch(1).published()).isOne();
        assertThat(publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(providerAttempts()).isOne();
        assertThat(relay().relayBatch(1).claimed()).isZero();
    }

    @Test void providerFailure_persistsNewGeneration_thenOldMessageCannotSendAgain() {
        UUID originalMessage = messageId();
        when(sender.sendNotification(anyString(), any())).thenThrow(new IllegalStateException("provider down"));
        delivery.deliver(deliveryId, 0);
        assertThat(providerStatus()).isEqualTo("RETRY");
        assertThat(generation()).isOne();
        assertThat(messageId()).isNotEqualTo(originalMessage);
        assertThat(publicationStatus()).isEqualTo("PENDING");
        delivery.deliver(deliveryId, 0);
        verify(sender, times(1)).sendNotification(anyString(), any());
        assertThat(relay().relayBatch(1).claimed()).isZero();
        time(NOW.plusSeconds(6));
        doReturn(SettlementPushSenderPort.SendResult.SENT).when(sender).sendNotification(anyString(), any());
        doAnswer(call -> {
            NotificationDeliveryPublisherPort.DeliveryMessage message = call.getArgument(0);
            assertThat(message.generation()).isOne();
            delivery.deliver(message.deliveryId(), message.generation()); return null;
        }).when(publisher).publish(any());
        assertThat(relay().relayBatch(1).published()).isOne();
        assertThat(providerStatus()).isEqualTo("SUCCEEDED");
        assertThat(providerAttempts()).isEqualTo(2);
    }

    @Test void expiredPublicationLease_andPublishedButUnconsumed_keepSameMessageAndFenceOldAck() {
        var first = store.claimPublication(NOW, NOW.plusSeconds(30), NOW.minusMinutes(2)).orElseThrow();
        assertThat(store.claimPublication(NOW.plusSeconds(1), NOW.plusSeconds(31), NOW.minusMinutes(2))).isEmpty();
        var second = store.claimPublication(NOW.plusSeconds(31), NOW.plusSeconds(61), NOW.minusMinutes(2)).orElseThrow();
        assertThat(second.message()).isEqualTo(first.message());
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(store.publicationSucceeded(first, NOW.plusSeconds(32))).isFalse();
        assertThat(store.publicationSucceeded(second, NOW.plusSeconds(32))).isTrue();
        var third = store.claimPublication(NOW.plusMinutes(3), NOW.plusMinutes(4), NOW.plusMinutes(1)).orElseThrow();
        assertThat(third.message()).isEqualTo(first.message());
        assertThat(providerAttempts()).isZero();
    }

    @Test void staleProviderLease_createsNewGeneration_andFencesLateCompletionAndAck() {
        var publication = store.claimPublication(NOW, NOW.plusMinutes(2), NOW.minusMinutes(2)).orElseThrow();
        var old = store.claimDelivery(deliveryId, 0, NOW).orElseThrow();
        assertThat(store.recoverStaleDeliveries(10, NOW.plusMinutes(1), NOW.plusMinutes(3), 5)).isOne();
        assertThat(generation()).isOne();
        assertThat(providerStatus()).isEqualTo("RETRY");
        assertThat(providerAttempts()).isOne();
        assertThat(store.completeDelivery(old, old.event().succeed(NOW.plusMinutes(3)), NOW.plusMinutes(3))).isFalse();
        assertThat(store.publicationSucceeded(publication, NOW.plusMinutes(3))).isFalse();
        time(NOW.plusMinutes(3));
        when(sender.sendNotification(anyString(), any())).thenReturn(SettlementPushSenderPort.SendResult.SENT);
        delivery.deliver(deliveryId, 1);
        assertThat(providerStatus()).isEqualTo("SUCCEEDED");
        assertThat(providerAttempts()).isEqualTo(2);
    }

    @Test void disabledRecipient_keepsInboxAndTerminatesProviderDelivery() {
        jdbc.update("UPDATE users SET active=false WHERE id=?", recipient);
        delivery.deliver(deliveryId, 0);
        assertThat(providerStatus()).isEqualTo("FAILED");
        verifyNoInteractions(sender);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notifications WHERE id=?", Integer.class, notificationId)).isOne();
    }

    @Test void sameLedgerGenerationWithAlteredEnvelope_isRejectedBeforeProviderClaim() {
        var canonical = store.findDeliveryMessage(deliveryId, 0).orElseThrow();
        List<DeliverNotificationCommand> altered = List.of(
                new DeliverNotificationCommand(UUID.randomUUID(), deliveryId, 0, recipient, canonical.occurredAt()),
                new DeliverNotificationCommand(canonical.messageId(), deliveryId, 0, recipient + 1, canonical.occurredAt()),
                new DeliverNotificationCommand(canonical.messageId(), deliveryId, 0, recipient, canonical.occurredAt().plusSeconds(1)));
        for (var command : altered) assertThatThrownBy(() -> delivery.deliver(command)).isInstanceOf(IllegalArgumentException.class);
        assertThat(providerAttempts()).isZero();
        verifyNoInteractions(sender);
        when(sender.sendNotification(anyString(), any())).thenReturn(SettlementPushSenderPort.SendResult.SENT);
        delivery.deliver(new DeliverNotificationCommand(canonical.messageId(), canonical.deliveryId(), canonical.generation(),
                canonical.recipientUserId(), canonical.occurredAt()));
        assertThat(providerStatus()).isEqualTo("SUCCEEDED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "2026-10-06T17:59:59,false", "2026-10-06T18:00:00,true",
            "2026-10-06T20:59:59,true", "2026-10-06T21:00:00,false"
    })
    void delayedAutomaticReminder_rechecksQuietBoundaryAndDefersWithoutProviderAttempt(LocalDateTime at, boolean quiet) {
        replaceWithReminder("AUTO_REMINDER:" + UUID.randomUUID() + ":123", at);
        UUID originalMessage = messageId();
        when(sender.sendNotification(anyString(), any())).thenReturn(SettlementPushSenderPort.SendResult.SENT);
        delivery.deliver(deliveryId, 0);
        if (!quiet) {
            assertThat(providerStatus()).isEqualTo("SUCCEEDED");
            assertThat(providerAttempts()).isOne();
            return;
        }
        assertThat(providerStatus()).isEqualTo("PENDING");
        assertThat(providerAttempts()).isZero();
        assertThat(generation()).isOne();
        assertThat(messageId()).isNotEqualTo(originalMessage);
        LocalDateTime resume = LocalDateTime.of(2026, 10, 6, 21, 0);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM settlement_notification_outbox WHERE id=?", LocalDateTime.class, deliveryId))
                .isEqualTo(resume);
        delivery.deliver(deliveryId, 0);
        verifyNoInteractions(sender);
        time(resume.minusSeconds(1)); delivery.deliver(deliveryId, 1);
        verifyNoInteractions(sender);
        time(resume); delivery.deliver(deliveryId, 1);
        assertThat(providerStatus()).isEqualTo("SUCCEEDED");
        assertThat(providerAttempts()).isOne();
        verify(sender, times(1)).sendNotification(anyString(), any());
    }

    @Test void manualReminder_isDeliverableDuringQuietHours() {
        replaceWithReminder("MANUAL_REMINDER:" + UUID.randomUUID(), LocalDateTime.of(2026, 10, 6, 18, 0));
        when(sender.sendNotification(anyString(), any())).thenReturn(SettlementPushSenderPort.SendResult.SENT);
        delivery.deliver(deliveryId, 0);
        assertThat(providerStatus()).isEqualTo("SUCCEEDED");
        assertThat(generation()).isZero();
    }

    @Test void directMode_automaticQuietDeferralRestoresClaimBudgetAndLaterDelivers() {
        replaceWithReminder("AUTO_REMINDER:" + UUID.randomUUID() + ":123", LocalDateTime.of(2026, 10, 6, 18, 0));
        assertThat(directWorker.processBatch(1).deferred()).isOne();
        assertThat(providerStatus()).isEqualTo("PENDING");
        assertThat(providerAttempts()).isZero();
        assertThat(generation()).isOne();
        verifyNoInteractions(sender);
        time(LocalDateTime.of(2026, 10, 6, 21, 0));
        when(sender.sendNotification(anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return SettlementPushSenderPort.SendResult.SENT;
        });
        assertThat(directWorker.processBatch(1).succeeded()).isOne();
        assertThat(providerAttempts()).isOne();
    }

    private void replaceWithReminder(String key, LocalDateTime at) {
        jdbc.update("DELETE FROM settlement_notification_outbox WHERE recipient_user_id=?", recipient);
        jdbc.update("DELETE FROM user_notifications WHERE recipient_user_id=?", recipient);
        reminderSettlement = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements(id,creator_id,title,total_amount,allocation_type,status,created_at,updated_at)
                VALUES (?,?,'지연 독촉',1000,'DUTCH_PAY','AWAITING_DEPOSIT',?,?)
                """, reminderSettlement, recipient, at.minusDays(1), at);
        var reminder = NotificationMessages.reminder(reminderSettlement, recipient, SettlementAllocationType.DUTCH_PAY,
                "요청자", key, "정산을 요청받은지 12시간이나 지났어요", at.minusHours(1));
        recorder.record(reminder); notificationId = reminder.id();
        deliveryId = jdbc.queryForObject("SELECT id FROM settlement_notification_outbox WHERE notification_id=?", UUID.class, notificationId);
        time(at);
    }

    private NotificationDeliveryRelay relay() { return new NotificationDeliveryRelay(store, publisher, new SettlementNotificationRetryPolicy(), clock); }
    private void time(LocalDateTime at) { when(clock.instant()).thenReturn(at.toInstant(ZoneOffset.UTC)); when(clock.getZone()).thenReturn(ZoneOffset.UTC); }
    private String providerStatus() { return jdbc.queryForObject("SELECT status FROM settlement_notification_outbox WHERE id=?", String.class, deliveryId); }
    private String publicationStatus() { return jdbc.queryForObject("SELECT publication_status FROM settlement_notification_outbox WHERE id=?", String.class, deliveryId); }
    private Integer providerAttempts() { return jdbc.queryForObject("SELECT attempt_count FROM settlement_notification_outbox WHERE id=?", Integer.class, deliveryId); }
    private Long generation() { return jdbc.queryForObject("SELECT delivery_generation FROM settlement_notification_outbox WHERE id=?", Long.class, deliveryId); }
    private UUID messageId() { return jdbc.queryForObject("SELECT delivery_message_id FROM settlement_notification_outbox WHERE id=?", UUID.class, deliveryId); }
}
