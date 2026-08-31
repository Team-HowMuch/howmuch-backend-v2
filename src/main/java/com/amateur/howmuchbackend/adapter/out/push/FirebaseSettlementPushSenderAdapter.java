package com.amateur.howmuchbackend.adapter.out.push;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
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
public class FirebaseSettlementPushSenderAdapter implements SettlementPushSenderPort {
    private static final String PARTICIPATION_TYPE = "SETTLEMENT_PARTICIPATION";
    private static final String PARTICIPATION_TITLE = "정산 참여 알림";
    private static final String PARTICIPATION_BODY = "앱에서 정산 참여 요청을 확인해 주세요.";
    private static final String REMINDER_TYPE = "SETTLEMENT_REMINDER";
    private static final String REMINDER_TITLE = "정산 요청 알림";
    private static final String REMINDER_BODY = "앱에서 정산 요청을 확인해 주세요.";

    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;

    @Override
    public SendResult sendParticipationRequest(
            String firebaseInstallationId,
            UUID settlementId,
            String shareLink
    ) {
        return send(Message.builder()
                .setFid(firebaseInstallationId)
                .setNotification(Notification.builder()
                        .setTitle(PARTICIPATION_TITLE)
                        .setBody(PARTICIPATION_BODY)
                        .build())
                .putData("type", PARTICIPATION_TYPE)
                .putData("settlementId", settlementId.toString())
                .putData("shareLink", shareLink)
                .build());
    }

    @Override
    public SendResult sendReminder(String firebaseInstallationId, UUID settlementId) {
        return send(Message.builder()
                .setFid(firebaseInstallationId)
                .setNotification(Notification.builder()
                        .setTitle(REMINDER_TITLE)
                        .setBody(REMINDER_BODY)
                        .build())
                .putData("type", REMINDER_TYPE)
                .putData("settlementId", settlementId.toString())
                .build());
    }

    private SendResult send(Message message) {
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getIfAvailable();
        if (firebaseMessaging == null) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE
            );
        }

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
