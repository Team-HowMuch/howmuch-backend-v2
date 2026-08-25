package com.amateur.howmuchbackend.application.group.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.status.GroupStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

@Component
public class GroupMemberExitPolicy {
    private static final Set<SettlementStatus> BLOCKED_STATUSES = Set.of(
            SettlementStatus.REQUESTED,
            SettlementStatus.AWAITING_DEPOSIT,
            SettlementStatus.SUSPENDED
    );

    public void validate(
            GroupMember target,
            GroupMemberExitType exitType,
            Long requesterUserId,
            Set<SettlementStatus> settlementStatuses
    ) {
        Objects.requireNonNull(target, "종료 대상 그룹멤버는 필수입니다.");
        Objects.requireNonNull(exitType, "그룹멤버 종료 유형은 필수입니다.");
        Objects.requireNonNull(requesterUserId, "그룹멤버 종료 요청자 ID는 필수입니다.");
        Objects.requireNonNull(settlementStatuses, "그룹멤버 정산 상태는 필수입니다.");

        if (!target.isActive()) {
            throw ApplicationException.of(GroupStatusCode.GROUP_MEMBER_NOT_FOUND);
        }
        if (target.isOwner()
                || (exitType == GroupMemberExitType.OWNER_REMOVE
                && target.userId().equals(requesterUserId))) {
            throw ApplicationException.of(GroupStatusCode.GROUP_OWNER_TRANSFER_REQUIRED);
        }
        if (settlementStatuses.stream().anyMatch(BLOCKED_STATUSES::contains)) {
            throw ApplicationException.of(
                    GroupStatusCode.GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT
            );
        }
    }
}
