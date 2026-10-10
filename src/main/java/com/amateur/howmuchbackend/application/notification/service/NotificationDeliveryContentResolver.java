package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.out.notification.LoadNotificationPort;
import com.amateur.howmuchbackend.application.notification.policy.NotificationDeliveryTimingPolicy;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementShareLinkStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.domain.notification.NotificationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/** Loads and validates content in a short transaction; provider I/O happens after it returns. */
@Component
@RequiredArgsConstructor
public class NotificationDeliveryContentResolver {
    private final LoadSettlementPort settlements;
    private final LoadUserPort users;
    private final SettlementShareLinkStorePort links;
    private final SettlementLinkTokenPort linkTokens;
    private final LoadNotificationPort notifications;
    private final NotificationDeliveryTimingPolicy timing;

    public PreparedDelivery prepare(SettlementNotificationOutbox event, LocalDateTime now) {
        if (event.settlementId() != null && settlements.findByIdForUpdate(event.settlementId()).isEmpty())
            return PreparedDelivery.unavailable(SettlementNotificationFailureCode.TARGET_UNAVAILABLE);
        var candidate = users.findById(event.recipientUserId());
        if (candidate.isEmpty() || !candidate.get().active() || candidate.get().fcmToken() == null
                || candidate.get().fcmToken().isBlank())
            return PreparedDelivery.unavailable(SettlementNotificationFailureCode.TARGET_UNAVAILABLE);
        String token = candidate.get().fcmToken();
        if (event.notificationId() != null) {
            var notification = notifications.findById(event.notificationId());
            if (notification.isEmpty() || notification.get().target() == null
                    || !event.recipientUserId().equals(notification.get().recipientUserId())
                    || !Objects.equals(event.settlementId(), notification.get().settlementId()))
                return PreparedDelivery.unavailable(SettlementNotificationFailureCode.TARGET_UNAVAILABLE);
            var n = notification.get();
            String type = n.type() == NotificationType.SETTLEMENT_REQUEST ? "SETTLEMENT_PARTICIPATION"
                    : n.type().name();
            return new PreparedDelivery(token, new SettlementPushSenderPort.NotificationMessage(
                    n.id(), n.settlementId(), type, n.allocationType(), n.title(), n.message(), n.target()),
                    null, null, n.type() == NotificationType.SETTLEMENT_REMINDER
                            && n.deduplicationKey().startsWith("AUTO_REMINDER:") ? timing.resumeAutomaticAt(now) : null);
        }
        var linkCandidate = links.findBySettlementId(event.settlementId());
        if (linkCandidate.isEmpty()) return PreparedDelivery.unavailable(SettlementNotificationFailureCode.LINK_UNAVAILABLE);
        var link = linkCandidate.get();
        if (link.rawToken() == null || !link.link().isUsableAt(now)
                || !link.link().tokenHash().equals(linkTokens.hash(link.rawToken())))
            return PreparedDelivery.unavailable(SettlementNotificationFailureCode.LINK_UNAVAILABLE);
        return new PreparedDelivery(token, null, linkTokens.url(link.rawToken()), null, null);
    }

    public record PreparedDelivery(String token, SettlementPushSenderPort.NotificationMessage message,
            String legacyShareLink, SettlementNotificationFailureCode failure, LocalDateTime deferUntil) {
        private static PreparedDelivery unavailable(SettlementNotificationFailureCode failure) {
            return new PreparedDelivery(null, null, null, failure, null);
        }
        public SettlementPushSenderPort.SendResult send(SettlementPushSenderPort sender, SettlementNotificationOutbox event) {
            if (failure != null) throw new IllegalStateException("Unavailable delivery has no provider request.");
            return message != null ? sender.sendNotification(token, message)
                    : sender.sendParticipationRequest(token, event.settlementId(), legacyShareLink);
        }
        // The record contains a provider token/share link. Never expose it through default record logging.
        @Override public String toString() { return "PreparedDelivery[available=" + (failure == null) + "]"; }
    }
}
