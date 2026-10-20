package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import org.springframework.stereotype.Component;

/** 반복 설정 조회·해지 권한. 생성자는 그룹 탈퇴·삭제 후에도 조회할 수 있고, 활성 그룹 멤버는 조회만 가능하다. */
@Component
public class RecurringSettlementAccessPolicy {
    public void requireRead(RecurringSettlement recurring, Long actorId, boolean activeGroupMember) {
        if (!recurring.creatorId().equals(actorId) && !activeGroupMember) {
            denied();
        }
    }

    public void requireCreator(RecurringSettlement recurring, Long actorId) {
        if (!recurring.creatorId().equals(actorId)) {
            denied();
        }
    }

    private static void denied() {
        throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_ACCESS_DENIED);
    }
}
