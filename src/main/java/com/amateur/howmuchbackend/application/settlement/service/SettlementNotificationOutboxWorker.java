package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.notification.service.NotificationDeliveryContentResolver;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementNotificationOutboxPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
@Slf4j
public class SettlementNotificationOutboxWorker {
    private final SettlementNotificationOutboxPort outboxPort;
    private final SettlementPushSenderPort pushSenderPort;
    private final SettlementNotificationRetryPolicy retryPolicy;
    private final TransactionTemplate deliveryTransaction;
    private final NotificationDeliveryContentResolver contentResolver;
    private final Clock clock;

    public SettlementNotificationOutboxWorker(SettlementNotificationOutboxPort outboxPort,
            SettlementPushSenderPort pushSenderPort,
            SettlementNotificationRetryPolicy retryPolicy,
            PlatformTransactionManager transactionManager,
            NotificationDeliveryContentResolver contentResolver, Clock clock
    ) {
        this.outboxPort = outboxPort;
        this.pushSenderPort = pushSenderPort;
        this.retryPolicy = retryPolicy;
        this.deliveryTransaction = new TransactionTemplate(transactionManager);
        this.contentResolver = contentResolver;
        this.clock = clock;
    }

    public BatchResult processBatch(int batchSize) {
        LocalDateTime now = now();
        int recovered = outboxPort.recoverStale(
                batchSize,
                retryPolicy.staleBefore(now),
                now,
                retryPolicy.maxAttempts()
        );
        int claimed = 0;
        int succeeded = 0;
        int retried = 0;
        int failed = 0;
        int ownershipLost = 0;
        int deferred = 0;
        while (claimed < batchSize) {
            Optional<SettlementNotificationOutbox> candidate = outboxPort.claimNextReady(
                    now()
            );
            if (candidate.isEmpty()) {
                break;
            }
            claimed++;
            SettlementNotificationOutbox event = candidate.get();
            ProcessingResult result;
            try {
                result = process(event);
            } catch (RuntimeException exception) {
                log.warn(
                        "Settlement notification processing transaction failed. "
                                + "eventId={}, settlementId={}, attemptCount={}, failureType={}",
                        event.id(),
                        event.settlementId(),
                        event.attemptCount(),
                        exception.getClass().getSimpleName()
                );
                result = retryOrFail(event);
            }
            switch (result) {
                case SUCCEEDED -> succeeded++;
                case RETRIED -> retried++;
                case FAILED -> failed++;
                case OWNERSHIP_LOST -> ownershipLost++;
                case DEFERRED -> deferred++;
            }
        }
        return new BatchResult(
                recovered,
                claimed,
                succeeded,
                retried,
                failed,
                ownershipLost, deferred
        );
    }

    private ProcessingResult process(SettlementNotificationOutbox event) {
        try {
            var prepared = deliveryTransaction.execute(status -> contentResolver.prepare(event, now()));
            if (prepared.failure() != null) return fail(event, prepared.failure());
            if (prepared.deferUntil() != null) {
                return Boolean.TRUE.equals(deliveryTransaction.execute(status -> outboxPort.deferProcessing(event,
                        prepared.deferUntil(), now()))) ? ProcessingResult.DEFERRED : ProcessingResult.OWNERSHIP_LOST;
            }
            // No DB transaction or row lock is held while FCM/APNs accepts the request.
            var result = prepared.send(pushSenderPort, event);
            return result == SettlementPushSenderPort.SendResult.INVALID_TARGET
                    ? fail(event, SettlementNotificationFailureCode.INVALID_TARGET) : succeed(event);
        } catch (RuntimeException exception) {
            log.warn("Notification delivery failed. eventId={}, attemptCount={}, failureType={}",
                    event.id(), event.attemptCount(), exception.getClass().getSimpleName());
            return retryOrFail(event);
        }
    }

    private ProcessingResult succeed(SettlementNotificationOutbox event) {
        LocalDateTime now = now();
        return transition(event.succeed(now), now, ProcessingResult.SUCCEEDED);
    }

    private ProcessingResult retryOrFail(SettlementNotificationOutbox event) {
        LocalDateTime now = now();
        if (!retryPolicy.canRetry(event.attemptCount())) {
            return transition(
                    event.fail(SettlementNotificationFailureCode.DELIVERY_ERROR, now),
                    now,
                    ProcessingResult.FAILED
            );
        }
        return transition(
                event.retry(
                        SettlementNotificationFailureCode.DELIVERY_ERROR,
                        retryPolicy.nextAttemptAt(event.attemptCount(), now),
                        now
                ),
                now,
                ProcessingResult.RETRIED
        );
    }

    private ProcessingResult fail(
            SettlementNotificationOutbox event,
            SettlementNotificationFailureCode failureCode
    ) {
        LocalDateTime now = now();
        return transition(event.fail(failureCode, now), now, ProcessingResult.FAILED);
    }

    private ProcessingResult transition(
            SettlementNotificationOutbox event,
            LocalDateTime transitionedAt,
            ProcessingResult successResult
    ) {
        if (Boolean.TRUE.equals(deliveryTransaction.execute(status -> outboxPort.transitionFromProcessing(event, transitionedAt)))) {
            return successResult;
        }
        log.warn(
                "Settlement participation notification ownership was lost. "
                        + "eventId={}, attemptCount={}, targetStatus={}",
                event.id(),
                event.attemptCount(),
                event.status()
        );
        return ProcessingResult.OWNERSHIP_LOST;
    }

    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }

    private enum ProcessingResult {
        SUCCEEDED,
        RETRIED,
        FAILED,
        OWNERSHIP_LOST, DEFERRED
    }

    public record BatchResult(
            int recovered,
            int claimed,
            int succeeded,
            int retried,
            int failed,
            int ownershipLost, int deferred
    ) {
        public BatchResult(int recovered, int claimed, int succeeded, int retried, int failed, int ownershipLost) {
            this(recovered, claimed, succeeded, retried, failed, ownershipLost, 0);
        }
    }
}
