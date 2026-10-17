package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.ProcessRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementExecutionResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.LockGroupMemberRosterPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementOccurrenceStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementEligibilityPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementOccurrence;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도래 회차 하나를 실제 정산으로 발행한다. 저장된 생성자·그룹 사실만 사용하고 사용자 JWT·CurrentActor를
 * 쓰지 않는다. 새 정산·참여자·지출·배분·Share·링크·알림·카테고리·회차·다음 일정을 하나의 업무 트랜잭션으로
 * 저장하며, 실패하면 전부 롤백된다.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementWorker implements ProcessRecurringSettlementUseCase {
    private final LoadRecurringSettlementPort recurring;
    private final SaveRecurringSettlementPort saveRecurring;
    private final RecurringSettlementOccurrenceStorePort occurrences;
    private final LoadUserPort users;
    private final LoadAccountPort accounts;
    private final ExternalConnectionStorePort connections;
    private final LoadGroupPort groups;
    private final LockGroupMemberRosterPort rosters;
    private final SaveSettlementPort settlements;
    private final SaveSettlementParticipantPort participants;
    private final SaveSettlementExpensePort expenses;
    private final SettlementDutchPayAllocationStorePort allocations;
    private final SettlementRequestWriter requestWriter;
    private final RecurringSettlementEligibilityPolicy eligibility;
    private final Clock clock;

    @Override
    @Transactional
    public RecurringSettlementExecutionResult process(UUID id, LocalDateTime scheduledFor) {
        if (occurrences.findBySchedule(id, scheduledFor).isPresent()) {
            return RecurringSettlementExecutionResult.ALREADY_PUBLISHED;
        }
        var snapshot = load(id, false);
        // Participant/account choices are immutable, so discover IDs before acquiring shared locks.
        Map<Long, User> currentUsers = new HashMap<>();
        snapshot.participants().stream().map(RecurringSettlement.Participant::userId).sorted()
                .forEach(userId -> users.findByIdForUpdate(userId).ifPresent(user -> currentUsers.put(userId, user)));
        var account = accounts.findActiveByIdAndUserIdForUpdate(snapshot.receivingAccountId(), snapshot.creatorId())
                .filter(value -> connections.findActiveByIdAndUserId(value.externalConnectionId(), snapshot.creatorId()).isPresent())
                .orElse(null);
        var value = load(id, true);
        if (occurrences.findBySchedule(id, scheduledFor).isPresent()) {
            return RecurringSettlementExecutionResult.ALREADY_PUBLISHED;
        }
        if (value.status() == RecurringSettlement.Status.CANCELLED) {
            return RecurringSettlementExecutionResult.CANCELLED;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!scheduledFor.equals(value.nextRunAt()) || scheduledFor.isAfter(now)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_EXECUTION_NOT_DUE);
        }
        var group = groups.findByIdForUpdate(value.groupId()).orElse(null);
        var roster = rosters.lockAndLoad(value.groupId()).orElse(null);
        var reason = eligibility.cancellationReason(value.groupId(), value.creatorId(),
                value.participants().stream().map(RecurringSettlement.Participant::userId).toList(),
                group, roster, currentUsers, account);
        if (reason != null) {
            saveRecurring.update(value.cancel(reason, now));
            return RecurringSettlementExecutionResult.CANCELLED;
        }
        var settlement = settlements.save(Settlement.draft(value.creatorId(), value.title(), value.totalAmount(), now)
                .selectReceivingAccount(value.receivingAccountId(), now)
                .selectAllocationType(SettlementAllocationType.DUTCH_PAY, now));
        List<SettlementDutchPayAllocation> amounts = new ArrayList<>();
        for (var person : value.participants()) {
            // The netting reader recognizes exactly one creator party as REGISTERED_USER with no
            // source group; every other participant must be a GROUP_MEMBER of this group.
            var name = currentUsers.get(person.userId()).name();
            var participant = person.userId().equals(value.creatorId())
                    ? participants.save(SettlementParticipant.registeredUser(
                            settlement.id(), person.userId(), name, person.displayOrder(), now))
                    : participants.save(SettlementParticipant.groupMember(settlement.id(), value.groupId(),
                            person.userId(), name, person.displayOrder(), now));
            amounts.add(SettlementDutchPayAllocation.create(settlement.id(), participant.id(), person.amount(), now));
        }
        for (var expense : value.expenses()) {
            expenses.save(SettlementExpense.manual(settlement.id(), expense.description(), expense.amount(),
                    expense.displayOrder(), now));
        }
        allocations.replaceAll(settlement.id(), amounts);
        requestWriter.request(settlement.id(), value.creatorId(), now, value.categoryCode());
        occurrences.save(new RecurringSettlementOccurrence(UUID.randomUUID(), id, scheduledFor, settlement.id(), now));
        saveRecurring.update(value.published(scheduledFor, now));
        return RecurringSettlementExecutionResult.PUBLISHED;
    }

    private RecurringSettlement load(UUID id, boolean forUpdate) {
        return (forUpdate ? recurring.findByIdForUpdate(id) : recurring.findById(id))
                .orElseThrow(() -> ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_NOT_FOUND));
    }
}
