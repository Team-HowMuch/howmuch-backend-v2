package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class SettlementTransferReconciliationPolicy {
    public static final int DAILY_LOOKUPS = 100;
    public static final int DAILY_AUTOMATIC_LOOKUPS = 80;
    public static final Duration INITIAL_DELAY = Duration.ofSeconds(30);
    public static final Duration WINDOW = Duration.ofMinutes(10);
    public static final Duration LEASE = Duration.ofMinutes(6);

    public void validateSignal(Settlement settlement, SettlementParticipant participant,
                               SettlementShare share, long confirmedAmount) {
        if (!canReconcile(settlement) || settlement.isCreatedBy(participant.linkedUserId())
                || !participant.settlementId().equals(settlement.id())
                || !share.settlementId().equals(settlement.id())
                || !share.payerParticipantId().equals(participant.id())
                || share.amount() <= confirmedAmount) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_TRANSFER_INTENT_NOT_AVAILABLE);
        }
    }

    public boolean canReconcile(Settlement settlement) {
        return settlement.status() == SettlementStatus.REQUESTED
                || settlement.status() == SettlementStatus.AWAITING_DEPOSIT;
    }

    public Decision retry(SettlementDepositReconciliationJob job, LocalDateTime now, String code) {
        LocalDateTime next = now.plusSeconds(job.attemptCount() == 1 ? 90 : 300);
        if (job.attemptCount() >= job.maxAttempts() || !next.isBefore(job.expiresAt())) {
            return new Decision(SettlementDepositReconciliationStatus.EXHAUSTED, null, code);
        }
        return new Decision(SettlementDepositReconciliationStatus.RETRY, next, code);
    }

    public record Decision(SettlementDepositReconciliationStatus status,
                           LocalDateTime nextAttemptAt, String failureCode) {}
}
