package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementScope;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementStatusFilter;
import java.util.List;
import java.util.UUID;

/** 목록·홈 요약 전용 조회. 필터는 각각 SQL에서 독립적으로 적용된다. */
public interface LoadRecurringSettlementSummaryPort {
    List<RecurringSettlementSummaryReadModel> findGroupPage(Long groupId, RecurringSettlementScope scope,
            RecurringSettlementStatusFilter status, Long viewerId, UUID cursor, int limit);

    List<RecurringSettlementSummaryReadModel> findHome(Long viewerId, int limit);

    /**
     * 커서가 같은 그룹·상태·참여 구역의 페이지에 속하는지 확인한다. 다른 그룹·필터의 커서로 빈 페이지를
     * 돌려주지 않고 400으로 거부하기 위한 검증이다.
     */
    boolean cursorInPage(UUID cursor, Long groupId, RecurringSettlementScope scope,
            RecurringSettlementStatusFilter status, Long viewerId);
}
