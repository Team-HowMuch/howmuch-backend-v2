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

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FirebaseSettlementPushSenderAdapter implements SettlementPushSenderPort {
    private static final String PARTICIPATION_TYPE = "SETTLEMENT_PARTICIPATION";
    private static final String PARTICIPATION_TITLE = "정산 참여 알림";
    private static final String PARTICIPATION_BODY = "앱에서 정산 참여 요청을 확인해 주세요.";

    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;
    private final ApnsPushSender apnsPushSender;

    @Override
    public SendResult sendParticipationRequest(
            String firebaseInstallationId,
            UUID settlementId,
            String shareLink
    ) {
        if (isApnsToken(firebaseInstallationId)) {
            return apnsPushSender.send(firebaseInstallationId, PARTICIPATION_TITLE, PARTICIPATION_BODY,
                    Map.of("type", PARTICIPATION_TYPE, "settlementId", settlementId.toString(),
                            "shareLink", shareLink));
        }
        return send(Message.builder()
                .setToken(firebaseInstallationId)
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
    public SendResult sendNotification(String firebaseInstallationId, NotificationMessage notification) {
        Map<String, String> data = notificationData(notification);
        if (isApnsToken(firebaseInstallationId)) {
            return apnsPushSender.send(firebaseInstallationId, notification.title(), notification.body(),
                    data);
        }
        return send(Message.builder()
                .setToken(firebaseInstallationId)
                .setNotification(Notification.builder().setTitle(notification.title()).setBody(notification.body()).build())
                .putAllData(data)
                .build());
    }

    static Map<String, String> notificationData(NotificationMessage notification) {
        Map<String, String> data = new HashMap<>();
        data.put("notificationId", notification.notificationId().toString());
        data.put("type", notification.type());
        if (notification.settlementId() != null) data.put("settlementId", notification.settlementId().toString());
        if (notification.allocationType() != null) data.put("allocationType", notification.allocationType().name());
        if (notification.target() != null) {
            data.put("targetType", notification.target().type().name());
            data.put("targetId", notification.target().id());
        }
        return Map.copyOf(data);
    }

    private boolean isApnsToken(String token) {
        // ponytail: native iOS tokens are hex; add an explicit platform field if token formats overlap.
        return token != null && !token.isEmpty() && token.length() % 2 == 0
                && token.chars().allMatch(c -> c >= '0' && c <= '9'
                        || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F');
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
