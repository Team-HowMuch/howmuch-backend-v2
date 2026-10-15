package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.application.port.in.notification.DeliverNotificationUseCase;
import com.amateur.howmuchbackend.application.port.in.notification.command.DeliverNotificationCommand;
import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryPublisherPort.DeliveryMessage;
import com.amateur.howmuchbackend.application.port.out.notification.NotificationDeliveryStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class NotificationDeliveryService implements DeliverNotificationUseCase {
    private final NotificationDeliveryStorePort store;
    private final NotificationDeliveryContentResolver content;
    private final SettlementPushSenderPort sender;
    private final SettlementNotificationRetryPolicy retryPolicy;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public NotificationDeliveryService(NotificationDeliveryStorePort store, NotificationDeliveryContentResolver content,
            SettlementPushSenderPort sender, SettlementNotificationRetryPolicy retryPolicy, Clock clock,
            PlatformTransactionManager manager) {
        this.store = store;
        this.content = content;
        this.sender = sender;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void deliver(DeliverNotificationCommand command) {
        var stored = store.findDeliveryMessage(command.deliveryId(), command.generation());
        if (stored.isEmpty()) return; // Deleted rows and retired generations are harmless replays.
        var supplied = new DeliveryMessage(command.messageId(), command.deliveryId(), command.generation(),
                command.recipientUserId(), command.occurredAt());
        if (!stored.get().equals(supplied)) throw new IllegalArgumentException("Notification delivery envelope differs from its ledger.");
        deliver(command.deliveryId(), command.generation());
    }

    @Override
    public void deliver(UUID deliveryId, long generation) {
        // Fact loading failures propagate before the provider attempt is claimed; the Kafka retry keeps its budget.
        var preparedCandidate = transaction.execute(status -> store.findReadyDelivery(deliveryId, generation, now())
                .map(event -> content.prepare(event, now())));
        if (preparedCandidate.isEmpty()) return;
        var prepared = preparedCandidate.get();
        if (prepared.deferUntil() != null) {
            store.deferReadyDelivery(deliveryId, generation, prepared.deferUntil(), now());
            return;
        }
        var candidate = store.claimDelivery(deliveryId, generation, now());
        // A replay, concurrent claim, old generation, or terminal row has no provider side effect.
        if (candidate.isEmpty()) return;
        var claim = candidate.get();
        var event = claim.event();
        SettlementNotificationOutbox result;
        try {
            if (prepared.failure() != null) result = event.fail(prepared.failure(), now());
            else {
                // Deliberately outside the preparation and result transactions.
                var sent = prepared.send(sender, event);
                result = sent == SettlementPushSenderPort.SendResult.INVALID_TARGET
                        ? event.fail(SettlementNotificationFailureCode.INVALID_TARGET, now()) : event.succeed(now());
            }
        } catch (RuntimeException exception) {
            LocalDateTime failedAt = now();
            result = retryPolicy.canRetry(event.attemptCount())
                    ? event.retry(SettlementNotificationFailureCode.DELIVERY_ERROR,
                            retryPolicy.nextAttemptAt(event.attemptCount(), failedAt), failedAt)
                    : event.fail(SettlementNotificationFailureCode.DELIVERY_ERROR, failedAt);
        }
        // If persistence fails after provider acceptance, leave PROCESSING for recovery. No exactly-once push claim.
        store.completeDelivery(claim, result, now());
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
