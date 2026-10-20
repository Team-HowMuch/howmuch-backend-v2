package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.ReplayRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementOccurrenceStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 운영자 전용 수동 재실행. 공개 HTTP endpoint가 아니며, 동일 회차의 JobInstance를 그대로 쓴다. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementReplayService implements ReplayRecurringSettlementUseCase {
    private final LoadRecurringSettlementPort recurring;
    private final RecurringSettlementOccurrenceStorePort occurrences;
    private final RecurringSettlementExecutionPort executions;

    @Override
    public void replay(UUID id, LocalDateTime scheduledFor, boolean recoverStaleExecution) {
        var value = recurring.findById(id).orElseThrow(() ->
                ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_NOT_FOUND));
        var occurrence = occurrences.findBySchedule(id, scheduledFor);
        if (!scheduledFor.equals(value.nextRunAt()) && occurrence.isEmpty()) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_EXECUTION_NOT_DUE);
        }
        var state = executions.inspect(id, scheduledFor).state();
        if ((state == RecurringSettlementExecutionPort.State.RUNNING && !recoverStaleExecution)
                || state == RecurringSettlementExecutionPort.State.UNKNOWN
                || (state == RecurringSettlementExecutionPort.State.COMPLETED && occurrence.isEmpty())) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_EXECUTION_NOT_DUE);
        }
        var result = executions.launch(id, scheduledFor, recoverStaleExecution
                ? RecurringSettlementExecutionPort.Mode.RECOVER_MANUAL : RecurringSettlementExecutionPort.Mode.MANUAL,
                RecurringSettlement.MAX_AUTOMATIC_ATTEMPTS);
        if (result == RecurringSettlementExecutionPort.LaunchResult.TRANSIENT_FAILURE
                || result == RecurringSettlementExecutionPort.LaunchResult.UNEXPECTED_FAILURE) {
            throw new IllegalStateException("RECURRING_MANUAL_LAUNCH_FAILED");
        }
        if (executions.inspect(id, scheduledFor).state() != RecurringSettlementExecutionPort.State.COMPLETED) {
            throw new IllegalStateException("RECURRING_MANUAL_EXECUTION_NOT_COMPLETED");
        }
    }
}
