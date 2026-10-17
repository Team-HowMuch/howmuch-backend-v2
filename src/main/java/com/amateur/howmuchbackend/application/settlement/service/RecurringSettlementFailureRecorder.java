package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.RecordRecurringSettlementFailureUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecordRecurringSettlementFailureCommand;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementOccurrenceStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveRecurringSettlementPort;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업무 트랜잭션이 롤백된 뒤 별도 짧은 트랜잭션에서 실패 요약을 남긴다. 같은 예정 회차가 아직 미발행일
 * 때만 반영하므로, 이미 성공한 회차에 늦은 실패 기록이 덮이지 않는다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementFailureRecorder implements RecordRecurringSettlementFailureUseCase {
    private final LoadRecurringSettlementPort recurring;
    private final SaveRecurringSettlementPort save;
    private final RecurringSettlementOccurrenceStorePort occurrences;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(RecordRecurringSettlementFailureCommand command) {
        var found = recurring.findByIdForUpdate(command.recurringSettlementId());
        if (found.isEmpty()) {
            return;
        }
        var value = found.get();
        if (value.status() != RecurringSettlement.Status.ACTIVE
                || !command.scheduledFor().equals(value.nextRunAt())
                || occurrences.findBySchedule(value.id(), command.scheduledFor()).isPresent()) {
            return;
        }
        save.update(value.failed(command.transientFailure(), command.automatic(), LocalDateTime.now(clock)));
    }
}
