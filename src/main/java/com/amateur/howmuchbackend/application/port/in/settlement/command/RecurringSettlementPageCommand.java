package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementScope;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementStatusFilter;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import java.util.UUID;

/**
 * 목록 조회 조건. {@code scope}는 참여 여부 구역, {@code status}는 활성/해지 필터이며 두 필터는 각각
 * 독립적으로 페이지에 적용된다(전체를 한 페이지로 읽고 나눠 자르지 않는다).
 */
public record RecurringSettlementPageCommand(UUID cursor, int limit, RecurringSettlementScope scope,
        RecurringSettlementStatusFilter status) {
    public RecurringSettlementPageCommand {
        if (limit < 1 || limit > 100) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_CURSOR);
        }
        scope = scope == null ? RecurringSettlementScope.ALL : scope;
        status = status == null ? RecurringSettlementStatusFilter.ACTIVE : status;
    }
}
