package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementNotificationOutboxPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementShareLinkStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import com.amateur.howmuchbackend.domain.notification.NotificationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 참여 알림 Outbox Worker")
class SettlementNotificationOutboxWorkerTest {
    private static final UUID SETTLEMENT_ID =
            UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 9, 3, 12, 0);
    private static final String RAW_TOKEN = "r".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String URL = "https://howmuch.example/settlements/" + RAW_TOKEN;

    @Mock private SettlementNotificationOutboxPort outboxPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private SettlementShareLinkStorePort shareLinkStorePort;
    @Mock private SettlementLinkTokenPort linkTokenPort;
    @Mock private SettlementPushSenderPort pushSenderPort;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    @Mock private com.amateur.howmuchbackend.application.port.out.notification.LoadNotificationPort notifications;
    private SettlementNotificationOutboxWorker worker;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        worker = new SettlementNotificationOutboxWorker(
                outboxPort,
                loadSettlementPort,
                loadUserPort,
                shareLinkStorePort,
                linkTokenPort,
                pushSenderPort,
                new SettlementNotificationRetryPolicy(),
                notifications,
                transactionManager
        );
    }

    @Test
    @DisplayName("앞선 알림 발송을 완료한 뒤 다음 이벤트를 선점한다")
    void notificationOutbox_eachEvent_claimedBeforeDelivery() {
        SettlementNotificationOutbox first = processingEvent(1L);
        SettlementNotificationOutbox second = processingEvent(2L);
        when(outboxPort.recoverStale(eq(2), any(), any(), eq(5))).thenReturn(0);
        when(outboxPort.claimNextReady(any()))
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second));
        activeSettlement();
        when(loadUserPort.findById(1L)).thenReturn(Optional.of(user(1L, "fid-1")));
        when(loadUserPort.findById(2L)).thenReturn(Optional.of(user(2L, "fid-2")));
        when(shareLinkStorePort.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(
                new SettlementShareLinkStorePort.StoredSettlementShareLink(link(), RAW_TOKEN)
        ));
        when(linkTokenPort.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(linkTokenPort.url(RAW_TOKEN)).thenReturn(URL);
        when(pushSenderPort.sendParticipationRequest(any(), eq(SETTLEMENT_ID), eq(URL)))
                .thenReturn(SettlementPushSenderPort.SendResult.SENT);
        when(outboxPort.transitionFromProcessing(any(), any())).thenReturn(true);

        SettlementNotificationOutboxWorker.BatchResult result = worker.processBatch(2);

        assertThat(result.claimed()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.ownershipLost()).isZero();

        InOrder deliveryOrder = inOrder(outboxPort, pushSenderPort);
        deliveryOrder.verify(outboxPort).recoverStale(eq(2), any(), any(), eq(5));
        deliveryOrder.verify(outboxPort).claimNextReady(any());
        deliveryOrder.verify(pushSenderPort)
                .sendParticipationRequest("fid-1", SETTLEMENT_ID, URL);
        deliveryOrder.verify(outboxPort).transitionFromProcessing(any(), any());
        deliveryOrder.verify(outboxPort).claimNextReady(any());
        deliveryOrder.verify(pushSenderPort)
                .sendParticipationRequest("fid-2", SETTLEMENT_ID, URL);
        deliveryOrder.verify(outboxPort).transitionFromProcessing(any(), any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "SETTLEMENT_REQUEST,DUTCH_PAY", "SETTLEMENT_REQUEST,ITEM_ALLOCATION",
            "SETTLEMENT_REMINDER,DUTCH_PAY", "SETTLEMENT_REMINDER,ITEM_ALLOCATION"
    })
    void sendsInboxPayloadWithoutPublicLink(NotificationType type, SettlementAllocationType allocation) {
        var notification = new com.amateur.howmuchbackend.domain.notification.UserNotification(UUID.randomUUID(), 1L,
                SETTLEMENT_ID, type, allocation,
                "요청자", "알림 제목", "알림 내용", "test-key", null, CREATED_AT);
        var event = SettlementNotificationOutbox.pending(SETTLEMENT_ID, 1L, CREATED_AT, notification.id()).claim(CREATED_AT);
        when(outboxPort.claimNextReady(any())).thenReturn(Optional.of(event));
        activeSettlement();
        when(loadUserPort.findById(1L)).thenReturn(Optional.of(user(1L, "token")));
        when(notifications.findById(notification.id())).thenReturn(Optional.of(notification));
        when(pushSenderPort.sendNotification(any(), any())).thenReturn(SettlementPushSenderPort.SendResult.SENT);
        when(outboxPort.transitionFromProcessing(any(), any())).thenReturn(true);
        assertThat(worker.processBatch(1).succeeded()).isEqualTo(1);
        var message = org.mockito.ArgumentCaptor.forClass(SettlementPushSenderPort.NotificationMessage.class);
        org.mockito.Mockito.verify(pushSenderPort).sendNotification(eq("token"), message.capture());
        assertThat(message.getValue().notificationId()).isEqualTo(notification.id());
        assertThat(message.getValue().settlementId()).isEqualTo(notification.settlementId());
        assertThat(message.getValue().allocationType()).isEqualTo(notification.allocationType());
        assertThat(message.getValue().title()).isEqualTo(notification.title());
        assertThat(message.getValue().body()).isEqualTo(notification.message());
        assertThat(message.getValue().type()).isEqualTo(type.name().equals("SETTLEMENT_REQUEST")
                ? "SETTLEMENT_PARTICIPATION" : "SETTLEMENT_REMINDER");
        org.mockito.Mockito.verifyNoInteractions(shareLinkStorePort, linkTokenPort);
    }

    @Test
    void notificationOutbox_deliveryFailure_retriesWithSameId() {
        var notification = com.amateur.howmuchbackend.application.notification.service.NotificationMessages.request(
                SETTLEMENT_ID, 1L, com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType.DUTCH_PAY,
                "요청자", CREATED_AT);
        var event = SettlementNotificationOutbox.pending(SETTLEMENT_ID, 1L, CREATED_AT, notification.id()).claim(CREATED_AT);
        when(outboxPort.claimNextReady(any())).thenReturn(Optional.of(event));
        activeSettlement();
        when(loadUserPort.findById(1L)).thenReturn(Optional.of(user(1L, "token")));
        when(notifications.findById(notification.id())).thenReturn(Optional.of(notification));
        when(pushSenderPort.sendNotification(any(), any())).thenThrow(new IllegalStateException("push unavailable"));
        when(outboxPort.transitionFromProcessing(any(), any())).thenReturn(true);
        assertThat(worker.processBatch(1).retried()).isEqualTo(1);
        var transition = org.mockito.ArgumentCaptor.forClass(SettlementNotificationOutbox.class);
        org.mockito.Mockito.verify(outboxPort).transitionFromProcessing(transition.capture(), any());
        assertThat(transition.getValue().notificationId()).isEqualTo(notification.id());
    }

    @Test
    void notificationOutbox_deletedSettlement_isNotSent() {
        var event = processingEvent(1L);
        when(outboxPort.claimNextReady(any())).thenReturn(Optional.of(event));
        when(outboxPort.transitionFromProcessing(any(), any())).thenReturn(true);

        assertThat(worker.processBatch(1).failed()).isOne();
        verifyNoInteractions(loadUserPort, pushSenderPort);
    }

    private void activeSettlement() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(new Settlement(
                SETTLEMENT_ID, 1L, "정산", 1000L, SettlementStatus.REQUESTED, CREATED_AT, CREATED_AT)));
    }

    private SettlementNotificationOutbox processingEvent(Long recipientUserId) {
        return SettlementNotificationOutbox
                .pending(SETTLEMENT_ID, recipientUserId, CREATED_AT)
                .claim(CREATED_AT);
    }

    private SettlementLink link() {
        return new SettlementLink(
                UUID.randomUUID(),
                SETTLEMENT_ID,
                TOKEN_HASH,
                SettlementLinkStatus.ACTIVE,
                LocalDateTime.of(2099, 1, 1, 0, 0),
                CREATED_AT,
                CREATED_AT
        );
    }

    private User user(Long id, String fcmToken) {
        return new User(
                id,
                "identity-" + id,
                "참여자" + id,
                "participant_" + id,
                null,
                null,
                "0100000000" + id,
                "pin-hash",
                fcmToken,
                null,
                true,
                UserRole.USER,
                true,
                null,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }
}
