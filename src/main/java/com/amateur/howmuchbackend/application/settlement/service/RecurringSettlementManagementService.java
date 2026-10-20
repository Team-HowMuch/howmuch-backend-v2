package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementFormPolicy;

import com.amateur.howmuchbackend.application.port.in.settlement.CreateRecurringSettlementFromFormUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.DeleteRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.WriteRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.LockGroupMemberRosterPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveRecurringSettlementPort;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementAccessPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementEligibilityPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.account.Account;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementManagementService implements CreateRecurringSettlementFromFormUseCase,
        UpdateRecurringSettlementUseCase, DeleteRecurringSettlementUseCase {
    private final CurrentActorPort actor;
    private final LoadRecurringSettlementPort recurring;
    private final SaveRecurringSettlementPort save;
    private final LoadUserPort users;
    private final LoadAccountPort accounts;
    private final ExternalConnectionStorePort connections;
    private final LoadGroupPort groups;
    private final LockGroupMemberRosterPort rosters;
    private final RecurringSettlementAccessPolicy access;
    private final RecurringSettlementEligibilityPolicy eligibility;
    private final RecurrenceScheduleResolver schedules;
    private final SettlementCategoryCatalog categories;
    private final Clock clock;
    private final RecurringSettlementPresentationAssembler presentation;
    private final RecurringSettlementFormPolicy formPolicy;

    @Override
    @Transactional
    public RecurringSettlementResult create(Long groupId, WriteRecurringSettlementCommand command) {
        Long creator = actor.getCurrentActor().userId();
        var people = formPolicy.allocations(creator, command);
        var facts = lockPeopleAndAccounts(creator, command, people, null);
        validateEligibility(groupId, creator, command, people, facts);
        var schedule = schedules.resolve(command.frequency(), command.dayOfWeek(), command.dayOfMonth(), command.firstRunDate());
        var value = RecurringSettlement.create(groupId, creator, null, command.title(), command.categoryCode(),
                command.amount(), command.receivingAccountId(), schedule, people,
                List.of(new RecurringSettlement.Expense(command.title(), command.amount(), 0)), LocalDateTime.now(clock));
        save.save(value);
        return presentation.assemble(value);
    }

    @Override
    @Transactional
    public RecurringSettlementResult update(Long groupId, UUID id, WriteRecurringSettlementCommand command) {
        Long creator = actor.getCurrentActor().userId();
        var snapshot = load(groupId, id, false);
        access.requireCreator(snapshot, creator);
        var people = formPolicy.allocations(creator, command);
        var facts = lockPeopleAndAccounts(creator, command, people, snapshot);
        var current = load(groupId, id, true);
        access.requireCreator(current, creator);
        if (!current.equals(snapshot)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_CHANGED);
        }
        current.requireEditable(LocalDateTime.now(clock));
        validateEligibility(groupId, creator, command, people, facts);
        boolean sameCadence = current.schedule().frequency() == command.frequency()
                && Objects.equals(current.schedule().dayOfWeek(), command.dayOfWeek())
                && Objects.equals(current.schedule().dayOfMonth(), command.dayOfMonth());
        RecurrenceSchedule schedule = sameCadence && (command.firstRunDate() == null
                || command.firstRunDate().equals(current.schedule().firstRunDate())) ? current.schedule()
                : schedules.resolve(command.frequency(), command.dayOfWeek(), command.dayOfMonth(), command.firstRunDate());
        var value = current.revise(command.title(), command.categoryCode(), command.amount(),
                command.receivingAccountId(), schedule, people, LocalDateTime.now(clock));
        save.replace(value);
        return presentation.assemble(value);
    }

    @Override
    @Transactional
    public void delete(Long groupId, UUID id) {
        // Raw lookup includes deleted rows so repeated DELETE remains idempotent for its creator.
        var value = recurring.findByIdForUpdate(id).filter(item -> item.groupId().equals(groupId))
                .orElseThrow(() -> ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_NOT_FOUND));
        access.requireCreator(value, actor.getCurrentActor().userId());
        if (value.deletedAt() == null) {
            save.update(value.delete(LocalDateTime.now(clock)));
        }
    }

    private RecurringSettlement load(Long groupId, UUID id, boolean lock) {
        return (lock ? recurring.findByIdForUpdate(id) : recurring.findById(id))
                .filter(item -> item.groupId().equals(groupId) && item.deletedAt() == null)
                .orElseThrow(() -> ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_NOT_FOUND));
    }

    private Facts lockPeopleAndAccounts(Long creator, WriteRecurringSettlementCommand command,
            List<RecurringSettlement.Participant> people, RecurringSettlement previous) {
        var ids = new TreeSet<Long>();
        people.forEach(person -> ids.add(person.userId()));
        if (previous != null) previous.participants().forEach(person -> ids.add(person.userId()));
        Map<Long, User> found = new HashMap<>();
        ids.forEach(id -> users.findByIdForUpdate(id).ifPresent(user -> found.put(id, user)));
        var accountIds = new TreeSet<Long>();
        accountIds.add(command.receivingAccountId());
        if (previous != null) accountIds.add(previous.receivingAccountId());
        Map<Long, Account> foundAccounts = new HashMap<>();
        accountIds.forEach(id -> accounts.findActiveByIdAndUserIdForUpdate(id, creator)
                .ifPresent(account -> foundAccounts.put(id, account)));
        return new Facts(found, foundAccounts.get(command.receivingAccountId()));
    }

    private void validateEligibility(Long groupId, Long creator, WriteRecurringSettlementCommand command,
            List<RecurringSettlement.Participant> people, Facts facts) {
        if (command.categoryCode() == null || !categories.byCode().containsKey(command.categoryCode())) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_CATEGORY);
        }
        var account = facts.account();
        if (account != null && connections.findActiveByIdAndUserId(account.externalConnectionId(), creator).isEmpty()) {
            account = null;
        }
        var group = groups.findByIdForUpdate(groupId).orElse(null);
        var roster = rosters.lockAndLoad(groupId).orElse(null);
        if (eligibility.cancellationReason(groupId, creator, people.stream().map(RecurringSettlement.Participant::userId).toList(),
                group, roster, facts.users(), account) != null) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_FORM);
        }
    }

    private record Facts(Map<Long, User> users, Account account) {}
}
