package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementHomeUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementSummaryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈 요약. 현재 사용자가 참여 중인 ACTIVE 설정만, 현재 활성 그룹·활성 멤버 조건을 SQL에서 확인해 돌려준다.
 * 다른 사용자의 설정·계좌 정보는 포함하지 않는다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementHomeService implements GetRecurringSettlementHomeUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadRecurringSettlementSummaryPort summaries;
    private final RecurringSettlementSummaryAssembler assembler;

    @Override
    @Transactional(readOnly = true)
    public List<RecurringSettlementSummaryResult> getHome(int limit) {
        if (limit < 1 || limit > 20) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_CURSOR);
        }
        Long actorId = currentActorPort.getCurrentActor().userId();
        return assembler.assemble(summaries.findHome(actorId, limit));
    }
}
