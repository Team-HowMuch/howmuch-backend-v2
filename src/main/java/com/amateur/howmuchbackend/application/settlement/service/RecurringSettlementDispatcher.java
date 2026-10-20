package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.DispatchRecurringSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RecordRecurringSettlementFailureUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecordRecurringSettlementFailureCommand;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementRetryPolicy;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 도래 회차를 제한된 수만큼 읽어 자동 실행·재시작을 조정한다. Batch 상태를 직접 참조하지 않는다. */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementDispatcher implements DispatchRecurringSettlementsUseCase {
    private final LoadRecurringSettlementPort recurring;
    private final RecurringSettlementExecutionPort executions;
    private final RecordRecurringSettlementFailureUseCase failures;
    private final RecurringSettlementRetryPolicy retryPolicy;
    private final Clock clock;
    private LoadRecurringSettlementPort.DueOccurrence cursor;
    private LocalDateTime scheduledThrough;

    @Override
    public synchronized int dispatch(int batchSize) {
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("batchSize must be 1..1000");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // Fix the scheduled-through horizon at the start of a cursor scan cycle so newly due rows cannot
        // extend the tail forever. Retry eligibility still uses the moving now.
        if (cursor == null) {
            scheduledThrough = now;
        }
        var candidates = recurring.findDue(now, scheduledThrough, cursor, batchSize);
        if (candidates.size() == batchSize) {
            cursor = candidates.getLast();
        } else {
            // Reaching the tail ends the cycle; the next dispatch starts a fresh horizon at the current now.
            cursor = null;
            scheduledThrough = null;
        }
        int launched = 0;
        for (var candidate : candidates) {
            try {
                var value = recurring.findById(candidate.id());
                if (value.isEmpty() || !candidate.scheduledFor().equals(value.get().nextRunAt())) {
                    continue;
                }
                var state = executions.inspect(candidate.id(), candidate.scheduledFor());
                if (state.state() == RecurringSettlementExecutionPort.State.COMPLETED
                        || state.automaticAttempts() >= RecurringSettlement.MAX_AUTOMATIC_ATTEMPTS
                        || (state.state() == RecurringSettlementExecutionPort.State.FAILED
                            && !state.transientFailure() && value.get().lastFailureCode() == null)) {
                    log.warn("Recurring pending occurrence needs review. recurringId={}, scheduledFor={}",
                            candidate.id(), candidate.scheduledFor());
                }
                if (!retryPolicy.canAutomaticallyLaunch(value.get(), state, now)) {
                    continue;
                }
                var result = executions.launch(candidate.id(), candidate.scheduledFor(),
                        RecurringSettlementExecutionPort.Mode.AUTOMATIC, RecurringSettlement.MAX_AUTOMATIC_ATTEMPTS);
                if (result == RecurringSettlementExecutionPort.LaunchResult.LAUNCHED) {
                    launched++;
                } else if (result == RecurringSettlementExecutionPort.LaunchResult.TRANSIENT_FAILURE
                        || result == RecurringSettlementExecutionPort.LaunchResult.UNEXPECTED_FAILURE) {
                    failures.record(new RecordRecurringSettlementFailureCommand(candidate.id(), candidate.scheduledFor(),
                            result == RecurringSettlementExecutionPort.LaunchResult.TRANSIENT_FAILURE, true));
                }
            } catch (RuntimeException failure) {
                // Failure summaries can fail too; Batch attempt history still limits future launches.
                log.warn("Recurring dispatch needs review. recurringId={}, scheduledFor={}",
                        candidate.id(), candidate.scheduledFor());
            }
        }
        return launched;
    }
}
