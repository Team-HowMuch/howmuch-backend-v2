package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 반복 등록 원본 검증과 회차 발행 자격 판단. 저장된 사실을 확인하며, 자격 상실 시 대체 계좌·재배분 없이
 * 해지 사유만 돌려준다.
 */
@Component
public class RecurringSettlementEligibilityPolicy {
    public void validateSource(Long groupId, Long actorId, Settlement source,
            List<SettlementParticipant> participants, List<SettlementExpense> expenses) {
        if (!source.isCreatedBy(actorId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        if (source.status() != SettlementStatus.DRAFT || source.allocationType() != SettlementAllocationType.DUTCH_PAY
                || source.receivingAccountId() == null || participants.size() < 2 || expenses.isEmpty()
                || expenses.stream().anyMatch(expense -> expense.source() != SettlementExpenseSource.MANUAL)) {
            invalidSource();
        }
        for (SettlementParticipant participant : participants) {
            boolean creator = participant.isLinkedTo(actorId)
                    && participant.source() == SettlementParticipantSource.REGISTERED_USER;
            boolean groupMember = participant.source() == SettlementParticipantSource.GROUP_MEMBER
                    && Objects.equals(groupId, participant.sourceGroupId());
            if ((!creator && !groupMember) || participant.linkedUserId() == null) {
                invalidSource();
            }
        }
    }

    public RecurringSettlement.CancellationReason cancellationReason(Long groupId, Long creatorId,
            List<Long> userIds, Group group, GroupMemberRoster roster, Map<Long, User> users, Account account) {
        if (group == null || group.status() != GroupStatus.ACTIVE || !groupId.equals(group.id())
                || roster == null || !groupId.equals(roster.groupId())) {
            return RecurringSettlement.CancellationReason.GROUP_UNAVAILABLE;
        }
        if (!active(creatorId, users) || !roster.contains(creatorId)) {
            return RecurringSettlement.CancellationReason.CREATOR_UNAVAILABLE;
        }
        if (userIds.stream().anyMatch(id -> !active(id, users) || !roster.contains(id))) {
            return RecurringSettlement.CancellationReason.PARTICIPANT_UNAVAILABLE;
        }
        if (account == null || !creatorId.equals(account.userId()) || !account.isActiveWonDemandDeposit()) {
            return RecurringSettlement.CancellationReason.ACCOUNT_UNAVAILABLE;
        }
        return null;
    }

    private boolean active(Long id, Map<Long, User> users) {
        return users.containsKey(id) && users.get(id).active();
    }

    private static void invalidSource() {
        throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_SOURCE);
    }
}
