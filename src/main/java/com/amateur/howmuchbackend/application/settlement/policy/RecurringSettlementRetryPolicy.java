package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 자동 실행 여부. Batch 메타데이터의 자동 시도 수가 업무 실패 요약보다 우선하므로, 실패 요약 저장이
 * 유실돼도 3회를 넘겨 자동 실행하지 않는다.
 */
@Component
public class RecurringSettlementRetryPolicy {
    public boolean canAutomaticallyLaunch(RecurringSettlement recurring,
            RecurringSettlementExecutionPort.Execution execution, LocalDateTime now) {
        if (!recurring.automaticDue(now)
                || execution.automaticAttempts() >= RecurringSettlement.MAX_AUTOMATIC_ATTEMPTS) {
            return false;
        }
        if (execution.state() == RecurringSettlementExecutionPort.State.NEW) {
            return true;
        }
        if (execution.state() != RecurringSettlementExecutionPort.State.FAILED
                || !execution.lastAttemptAutomatic()) {
            return false;
        }
        // A stale business summary must not override a newer non-transient Batch failure.
        if (!execution.transientFailure()) {
            return false;
        }
        // Batch metadata bounds retries even when recording the business failure summary failed.
        long delay = execution.automaticAttempts() <= 1 ? 1 : 5;
        return execution.lastAttemptAt() != null && !execution.lastAttemptAt().plusMinutes(delay).isAfter(now);
    }
}
