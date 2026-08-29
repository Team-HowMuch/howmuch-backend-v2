package com.amateur.howmuchbackend.adapter.out.push;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReminderPushSenderPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FirebaseSettlementReminderPushSenderAdapter
        implements SettlementReminderPushSenderPort {
    private static final String MESSAGE_TYPE = "SETTLEMENT_REMINDER";
    private static final String TITLE = "정산 요청 알림";
    private static final String BODY = "앱에서 정산 요청을 확인해 주세요.";

    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;

    @Override
    public SendResult send(String firebaseInstallationId, UUID settlementId) {
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (firebaseMessaging == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE
            );
        }

        Message message = Message.builder()
                .setFid(firebaseInstallationId)
                .setNotification(Notification.builder().setTitle(TITLE).setBody(BODY).build())
                .putData("type", MESSAGE_TYPE)
                .putData("settlementId", settlementId.toString())
                .build();
        try {
            firebaseMessaging.send(message);
            return SendResult.SENT;
        } catch (FirebaseMessagingException exception) {
            if (exception.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                    || exception.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                return SendResult.INVALID_TARGET;
            }
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_FAILED,
                    exception
            );
        }
    }
}
