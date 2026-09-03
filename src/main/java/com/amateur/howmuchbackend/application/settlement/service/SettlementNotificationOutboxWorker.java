package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementNotificationOutboxPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementPushSenderPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementShareLinkStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementNotificationRetryPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementNotificationOutbox;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SettlementNotificationOutboxWorker {
    private final SettlementNotificationOutboxPort outboxPort;
    private final LoadUserPort loadUserPort;
    private final SettlementShareLinkStorePort shareLinkStorePort;
    private final SettlementLinkTokenPort linkTokenPort;
    private final SettlementPushSenderPort pushSenderPort;
    private final SettlementNotificationRetryPolicy retryPolicy;

    public BatchResult processBatch(int batchSize) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int recovered = outboxPort.recoverStale(
                batchSize,
                retryPolicy.staleBefore(now),
                now,
                retryPolicy.maxAttempts()
        );
        List<SettlementNotificationOutbox> claimed = outboxPort.claimReady(batchSize, now);

        int succeeded = 0;
        int retried = 0;
        int failed = 0;
        int ownershipLost = 0;
        for (SettlementNotificationOutbox event : claimed) {
            ProcessingResult result = process(event);
            switch (result) {
                case SUCCEEDED -> succeeded++;
                case RETRIED -> retried++;
                case FAILED -> failed++;
                case OWNERSHIP_LOST -> ownershipLost++;
            }
        }
        return new BatchResult(
                recovered,
                claimed.size(),
                succeeded,
                retried,
                failed,
                ownershipLost
        );
    }

    private ProcessingResult process(SettlementNotificationOutbox event) {
        try {
            Optional<User> recipientCandidate = loadUserPort.findById(event.recipientUserId());
            if (recipientCandidate.isEmpty()) {
                return fail(event, SettlementNotificationFailureCode.TARGET_UNAVAILABLE);
            }
            User recipient = recipientCandidate.get();
            if (!recipient.active()
                    || recipient.fcmToken() == null
                    || recipient.fcmToken().isBlank()) {
                return fail(event, SettlementNotificationFailureCode.TARGET_UNAVAILABLE);
            }

            Optional<SettlementShareLinkStorePort.StoredSettlementShareLink> linkCandidate =
                    shareLinkStorePort.findBySettlementId(event.settlementId());
            if (linkCandidate.isEmpty()) {
                return fail(event, SettlementNotificationFailureCode.LINK_UNAVAILABLE);
            }
            SettlementShareLinkStorePort.StoredSettlementShareLink storedLink = linkCandidate.get();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            if (storedLink.rawToken() == null || !storedLink.link().isUsableAt(now)) {
                return fail(event, SettlementNotificationFailureCode.LINK_UNAVAILABLE);
            }
            if (!storedLink.link().tokenHash().equals(linkTokenPort.hash(storedLink.rawToken()))) {
                return fail(event, SettlementNotificationFailureCode.LINK_UNAVAILABLE);
            }

            SettlementPushSenderPort.SendResult sendResult = pushSenderPort
                    .sendParticipationRequest(
                            recipient.fcmToken(),
                            event.settlementId(),
                            linkTokenPort.url(storedLink.rawToken())
                    );
            if (sendResult == SettlementPushSenderPort.SendResult.INVALID_TARGET) {
                return fail(event, SettlementNotificationFailureCode.INVALID_TARGET);
            }
            return succeed(event);
        } catch (RuntimeException exception) {
            log.warn(
                    "Settlement participation notification delivery failed. "
                            + "eventId={}, settlementId={}, recipientUserId={}, attemptCount={}, failureType={}",
                    event.id(),
                    event.settlementId(),
                    event.recipientUserId(),
                    event.attemptCount(),
                    exception.getClass().getSimpleName()
            );
            return retryOrFail(event);
        }
    }

    private ProcessingResult succeed(SettlementNotificationOutbox event) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return transition(event.succeed(now), now, ProcessingResult.SUCCEEDED);
    }

    private ProcessingResult retryOrFail(SettlementNotificationOutbox event) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return transition(event.fail(failureCode, now), now, ProcessingResult.FAILED);
    }

    private ProcessingResult transition(
            SettlementNotificationOutbox event,
            LocalDateTime transitionedAt,
            ProcessingResult successResult
    ) {
        if (outboxPort.transitionFromProcessing(event, transitionedAt)) {
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

    private enum ProcessingResult {
        SUCCEEDED,
        RETRIED,
        FAILED,
        OWNERSHIP_LOST
    }

    public record BatchResult(
            int recovered,
            int claimed,
            int succeeded,
            int retried,
            int failed,
            int ownershipLost
    ) {
    }
}
