package com.amateur.howmuchbackend.adapter.out.push;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderPushSenderPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Firebase 정산 독촉 푸시 Adapter")
class FirebaseSettlementReminderPushSenderAdapterTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");

    @Mock private ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;
    @Mock private FirebaseMessaging firebaseMessaging;

    @Test
    @DisplayName("앱 라우팅에 필요한 FID와 정산 식별자만 FCM에 전달한다")
    void sendsPrivacySafeReminderPayload() throws Exception {
        when(firebaseMessagingProvider.getIfAvailable()).thenReturn(firebaseMessaging);
        when(firebaseMessaging.send(any(Message.class))).thenReturn("message-id");
        var adapter = new FirebaseSettlementReminderPushSenderAdapter(
                firebaseMessagingProvider
        );

        var result = adapter.send("firebase-installation-id", SETTLEMENT_ID);

        assertThat(result).isEqualTo(SettlementReminderPushSenderPort.SendResult.SENT);
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(firebaseMessaging).send(messageCaptor.capture());
        assertThat(invoke(messageCaptor.getValue(), "getFid"))
                .isEqualTo("firebase-installation-id");
        assertThat(invoke(messageCaptor.getValue(), "getData"))
                .isEqualTo(Map.of(
                        "type", "SETTLEMENT_REMINDER",
                        "settlementId", SETTLEMENT_ID.toString()
                ));
    }

    @Test
    @DisplayName("Firebase가 비활성화된 환경은 성공으로 가장하지 않는다")
    void rejectsWhenFirebaseIsDisabled() {
        when(firebaseMessagingProvider.getIfAvailable()).thenReturn(null);
        var adapter = new FirebaseSettlementReminderPushSenderAdapter(
                firebaseMessagingProvider
        );

        assertThatThrownBy(() -> adapter.send("firebase-installation-id", SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE
                        ));
    }

    @Test
    @DisplayName("Firebase가 FID를 미등록 대상으로 판정하면 교체 가능한 실패로 반환한다")
    void returnsInvalidTarget() throws Exception {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(firebaseMessagingProvider.getIfAvailable()).thenReturn(firebaseMessaging);
        when(firebaseMessaging.send(any(Message.class))).thenThrow(exception);
        var adapter = new FirebaseSettlementReminderPushSenderAdapter(
                firebaseMessagingProvider
        );

        assertThat(adapter.send("expired-installation-id", SETTLEMENT_ID))
                .isEqualTo(SettlementReminderPushSenderPort.SendResult.INVALID_TARGET);
    }

    private Object invoke(Message message, String methodName) throws Exception {
        Method method = Message.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(message);
    }
}
