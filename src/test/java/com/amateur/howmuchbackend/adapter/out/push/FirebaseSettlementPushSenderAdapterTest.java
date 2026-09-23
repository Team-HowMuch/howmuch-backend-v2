package com.amateur.howmuchbackend.adapter.out.push;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Firebase 정산 푸시 Adapter")
class FirebaseSettlementPushSenderAdapterTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final String SHARE_LINK = "https://howmuch.example/settlements/link-token";
    private static final String FCM_TOKEN = "fcm-registration-token";

    @Mock private ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;
    @Mock private FirebaseMessaging firebaseMessaging;
    @Mock private ApnsPushSender apnsPushSender;

    @Test
    @DisplayName("참여와 독촉 알림은 개인정보 없이 앱 라우팅 데이터만 전달한다")
    void settlementPush_joinAndReminderPayloads_excludePrivateData() throws Exception {
        when(firebaseMessagingProvider.getIfAvailable()).thenReturn(firebaseMessaging);
        when(firebaseMessaging.send(any(Message.class))).thenReturn("message-id");
        var adapter = new FirebaseSettlementPushSenderAdapter(firebaseMessagingProvider, apnsPushSender);

        var participationResult = adapter.sendParticipationRequest(
                FCM_TOKEN,
                SETTLEMENT_ID,
                SHARE_LINK
        );
        var reminderResult = adapter.sendNotification(
                FCM_TOKEN,
                notification()
        );

        assertThat(participationResult).isEqualTo(SettlementPushSenderPort.SendResult.SENT);
        assertThat(reminderResult).isEqualTo(SettlementPushSenderPort.SendResult.SENT);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(firebaseMessaging, times(2)).send(messageCaptor.capture());
        Message participationMessage = messageCaptor.getAllValues().getFirst();
        Message reminderMessage = messageCaptor.getAllValues().getLast();
        assertThat(invoke(participationMessage, "getToken")).isEqualTo(FCM_TOKEN);
        assertThat(invoke(participationMessage, "getFid")).isNull();
        assertThat(invoke(reminderMessage, "getToken")).isEqualTo(FCM_TOKEN);
        assertThat(invoke(reminderMessage, "getFid")).isNull();
        assertThat(invoke(participationMessage, "getData"))
                .isEqualTo(Map.of(
                        "type", "SETTLEMENT_PARTICIPATION",
                        "settlementId", SETTLEMENT_ID.toString(),
                        "shareLink", SHARE_LINK
                ));
        assertThat(invoke(reminderMessage, "getData"))
                .isEqualTo(Map.of(
                        "type", "SETTLEMENT_REMINDER",
                        "settlementId", SETTLEMENT_ID.toString(),
                        "notificationId", SETTLEMENT_ID.toString(),
                        "allocationType", "DUTCH_PAY"
                ));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "SETTLEMENT_PARTICIPATION,DUTCH_PAY", "SETTLEMENT_PARTICIPATION,ITEM_ALLOCATION",
            "SETTLEMENT_REMINDER,DUTCH_PAY", "SETTLEMENT_REMINDER,ITEM_ALLOCATION"
    })
    void sendsAllocationAndStoredBody(String type, SettlementAllocationType allocation) throws Exception {
        when(firebaseMessagingProvider.getIfAvailable()).thenReturn(firebaseMessaging);
        var adapter = new FirebaseSettlementPushSenderAdapter(firebaseMessagingProvider, apnsPushSender);
        var notificationId = UUID.randomUUID();
        var body = "정산을 요청받은지 25시간이나 지났어요";
        adapter.sendNotification(FCM_TOKEN, new SettlementPushSenderPort.NotificationMessage(
                notificationId, SETTLEMENT_ID, type, allocation, "저장된 제목", body));
        var captured = ArgumentCaptor.forClass(Message.class);
        verify(firebaseMessaging).send(captured.capture());
        assertThat(invoke(captured.getValue(), "getData")).isEqualTo(Map.of(
                "notificationId", notificationId.toString(), "settlementId", SETTLEMENT_ID.toString(),
                "type", type, "allocationType", allocation.name()));
        var push = (com.google.firebase.messaging.Notification) invoke(captured.getValue(), "getNotification");
        assertThat(push).hasFieldOrPropertyWithValue("title", "저장된 제목")
                .hasFieldOrPropertyWithValue("body", body);
    }

    @Test
    @DisplayName("Firebase가 비활성화된 환경은 성공으로 가장하지 않는다")
    void rejectsWhenFirebaseIsDisabled() {
        when(firebaseMessagingProvider.getIfAvailable()).thenReturn(null);
        var adapter = new FirebaseSettlementPushSenderAdapter(firebaseMessagingProvider, apnsPushSender);

        assertThatThrownBy(() -> adapter.sendNotification(
                "firebase-installation-id",
                notification()
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE
                ));
    }

    @Test
    @DisplayName("Firebase가 FCM token을 미등록 대상으로 판정하면 교체 가능한 실패로 반환한다")
    void returnsInvalidTarget() throws Exception {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(firebaseMessagingProvider.getIfAvailable()).thenReturn(firebaseMessaging);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(exception);
        var adapter = new FirebaseSettlementPushSenderAdapter(firebaseMessagingProvider, apnsPushSender);

        assertThat(adapter.sendNotification("expired-installation-id", notification()))
                .isEqualTo(SettlementPushSenderPort.SendResult.INVALID_TARGET);
    }

    @Test
    @DisplayName("iOS APNs 토큰은 Firebase 대신 APNs로 발송한다")
    void routesNativeIosTokenToApns() throws Exception {
        String apnsToken = "a1".repeat(48);
        when(apnsPushSender.send(any(), any(), any(), any())).thenReturn(SettlementPushSenderPort.SendResult.SENT);
        var adapter = new FirebaseSettlementPushSenderAdapter(firebaseMessagingProvider, apnsPushSender);

        assertThat(adapter.sendNotification(apnsToken, notification()))
                .isEqualTo(SettlementPushSenderPort.SendResult.SENT);
        assertThat(adapter.sendParticipationRequest(apnsToken, SETTLEMENT_ID, SHARE_LINK))
                .isEqualTo(SettlementPushSenderPort.SendResult.SENT);
        verify(apnsPushSender).send(org.mockito.ArgumentMatchers.eq(apnsToken),
                org.mockito.ArgumentMatchers.eq("정산 리마인드"),
                org.mockito.ArgumentMatchers.eq("정산을 완료해 주세요."), any());
        verify(apnsPushSender).send(org.mockito.ArgumentMatchers.eq(apnsToken),
                org.mockito.ArgumentMatchers.eq("정산 참여 알림"),
                org.mockito.ArgumentMatchers.eq("앱에서 정산 참여 요청을 확인해 주세요."), any());
        org.mockito.Mockito.verifyNoInteractions(firebaseMessagingProvider);
    }

    private SettlementPushSenderPort.NotificationMessage notification() {
        return new SettlementPushSenderPort.NotificationMessage(SETTLEMENT_ID, SETTLEMENT_ID,
                "SETTLEMENT_REMINDER", SettlementAllocationType.DUTCH_PAY, "정산 리마인드", "정산을 완료해 주세요.");
    }

    private Object invoke(Message message, String methodName) throws Exception {
        Method method = Message.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(message);
    }
}
