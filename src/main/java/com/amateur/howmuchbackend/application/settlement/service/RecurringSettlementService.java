package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.CancelRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetGroupRecurringSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementSchedulePreviewUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementOccurrencesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateRecurringSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementPageCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementSchedulePreviewCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementOccurrenceResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPageResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSchedulePreviewResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementSummaryResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.LockGroupMemberRosterPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRecurringSettlementSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveRecurringSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementOccurrenceStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementAccessPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.RecurringSettlementEligibilityPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementRequestPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementService implements CreateRecurringSettlementUseCase,
        GetGroupRecurringSettlementsUseCase, GetRecurringSettlementUseCase,
        GetRecurringSettlementOccurrencesUseCase, CancelRecurringSettlementUseCase,
        GetRecurringSettlementSchedulePreviewUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadRecurringSettlementPort recurring;
    private final LoadRecurringSettlementSummaryPort summaries;
    private final SaveRecurringSettlementPort saveRecurring;
    private final RecurringSettlementOccurrenceStorePort occurrences;
    private final LoadSettlementPort settlements;
    private final LoadSettlementParticipantPort participants;
    private final LoadSettlementExpensePort expenses;
    private final SettlementDutchPayAllocationStorePort allocations;
    private final DeleteSettlementPort deleteSettlement;
    private final LoadGroupPort groups;
    private final LoadGroupMemberPort members;
    private final LockGroupMemberRosterPort rosters;
    private final LoadUserPort users;
    private final LoadAccountPort accounts;
    private final ExternalConnectionStorePort connections;
    private final RecurringSettlementEligibilityPolicy eligibility;
    private final RecurringSettlementAccessPolicy access;
    private final SettlementRequestPolicy requestPolicy;
    private final RecurrenceScheduleResolver scheduleResolver;
    private final RecurringSettlementSummaryAssembler summaryAssembler;
    private final SettlementCategoryCatalog categoryCatalog;
    private final Clock clock;
    private final RecurringSettlementPresentationAssembler presentation;

    @Override
    @Transactional
    public RecurringSettlementResult create(Long groupId, CreateRecurringSettlementCommand command) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        RecurrenceSchedule.requireValidCadence(command.frequency(), command.dayOfWeek(), command.dayOfMonth());

        // Replay an already-registered draft before resolving a new schedule: the original first run may be
        // past, and resolving first would wrongly reject or shift an identical retry.
        var existing = recurring.findBySourceSettlementId(command.sourceSettlementId());
        if (existing.isPresent()) {
            return replayRegistration(existing.get(), groupId, actorId, command);
        }
        requireSelectableCategory(command.categoryCode());
        RecurrenceSchedule schedule = scheduleResolver.resolve(command.frequency(), command.dayOfWeek(),
                command.dayOfMonth(), command.firstRunDate());

        // The same row lock is used by the existing immediate request and draft editors.
        var source = settlements.findByIdForUpdate(command.sourceSettlementId());
        // A concurrent registration may have consumed the draft while this transaction waited.
        existing = recurring.findBySourceSettlementId(command.sourceSettlementId());
        if (existing.isPresent()) {
            return replayRegistration(existing.get(), groupId, actorId, command);
        }
        Settlement draft = source.orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        var people = participants.findAllBySettlementId(draft.id());
        var costs = expenses.findAllBySettlementId(draft.id());
        eligibility.validateSource(groupId, actorId, draft, people, costs);
        var amounts = allocations.findAllBySettlementId(draft.id());
        LocalDateTime now = LocalDateTime.now(clock);
        requestPolicy.prepareShares(draft, people, costs, List.of(), amounts, now);
        Map<Long, User> activeUsers = new HashMap<>();
        people.stream().map(SettlementParticipant::linkedUserId).distinct().sorted()
                .forEach(id -> users.findByIdForUpdate(id).ifPresent(user -> activeUsers.put(id, user)));
        var account = accounts.findActiveByIdAndUserIdForUpdate(draft.receivingAccountId(), actorId)
                .filter(value -> connections.findActiveByIdAndUserId(value.externalConnectionId(), actorId).isPresent())
                .orElse(null);
        var group = groups.findByIdForUpdate(groupId).orElse(null);
        var roster = rosters.lockAndLoad(groupId).orElse(null);
        if (eligibility.cancellationReason(groupId, actorId,
                people.stream().map(SettlementParticipant::linkedUserId).toList(),
                group, roster, activeUsers, account) != null) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_SOURCE);
        }
        Map<UUID, SettlementDutchPayAllocation> byParticipant = amounts.stream().collect(
                Collectors.toMap(SettlementDutchPayAllocation::participantId, Function.identity()));
        var saved = RecurringSettlement.create(groupId, actorId, draft.id(), draft.title(), command.categoryCode(),
                draft.totalAmount(), draft.receivingAccountId(), schedule, people.stream().map(person ->
                        new RecurringSettlement.Participant(person.linkedUserId(),
                                byParticipant.get(person.id()).amount(), person.displayOrder())).toList(),
                costs.stream().map(cost -> new RecurringSettlement.Expense(
                        cost.description(), cost.amount(), cost.displayOrder())).toList(), now);
        saveRecurring.save(saved);
        deleteSettlement.delete(draft.id(), now);
        return presentation.assemble(saved);
    }

    private RecurringSettlementResult replayRegistration(RecurringSettlement existing, Long groupId, Long actorId,
            CreateRecurringSettlementCommand command) {
        access.requireCreator(existing, actorId);
        if (existing.deletedAt() != null) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_NOT_FOUND);
        }
        // Compare the request shape against the stored snapshot without future-date validation or a catalog lookup,
        // so a deactivated catalog entry or an elapsed first run never mutates or replaces the original template.
        if (!sameRequest(existing, groupId, command)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_SOURCE_ALREADY_USED);
        }
        return presentation.assemble(existing);
    }

    private boolean sameRequest(RecurringSettlement existing, Long groupId, CreateRecurringSettlementCommand command) {
        var schedule = existing.schedule();
        boolean cadence = schedule.frequency() == command.frequency()
                && Objects.equals(schedule.dayOfWeek(), command.dayOfWeek())
                && Objects.equals(schedule.dayOfMonth(), command.dayOfMonth());
        boolean firstRun = command.firstRunDate() == null || command.firstRunDate().equals(schedule.firstRunDate());
        return existing.groupId().equals(groupId)
                && existing.categoryCode().equals(command.categoryCode())
                && cadence && firstRun;
    }

    private void requireSelectableCategory(String categoryCode) {
        if (categoryCode == null || !categoryCatalog.byCode().containsKey(categoryCode)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_CATEGORY);
        }
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecurringSettlementSchedulePreviewResult preview(Long groupId, RecurringSettlementSchedulePreviewCommand command) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        if (!activeMember(groupId, actorId)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_ACCESS_DENIED);
        }
        return RecurringSettlementSchedulePreviewResult.from(scheduleResolver.resolve(command.frequency(),
                command.dayOfWeek(), command.dayOfMonth(), command.firstRunDate()));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecurringSettlementPageResult<RecurringSettlementSummaryResult> get(Long groupId,
            RecurringSettlementPageCommand query) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        if (!activeMember(groupId, actorId)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_ACCESS_DENIED);
        }
        if (query.cursor() != null && !summaries.cursorInPage(query.cursor(), groupId, query.scope(),
                query.status(), actorId)) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_CURSOR);
        }
        var values = summaries.findGroupPage(groupId, query.scope(), query.status(), actorId, query.cursor(),
                query.limit() + 1);
        boolean more = values.size() > query.limit();
        var page = values.subList(0, Math.min(values.size(), query.limit()));
        return new RecurringSettlementPageResult<>(summaryAssembler.assemble(page),
                more ? page.getLast().id() : null);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecurringSettlementResult get(Long groupId, UUID id) {
        var value = load(groupId, id);
        Long actorId = currentActorPort.getCurrentActor().userId();
        access.requireRead(value, actorId, activeMember(groupId, actorId));
        return presentation.assemble(value);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecurringSettlementPageResult<RecurringSettlementOccurrenceResult> get(Long groupId, UUID id,
            RecurringSettlementPageCommand query) {
        var value = load(groupId, id);
        Long actorId = currentActorPort.getCurrentActor().userId();
        access.requireRead(value, actorId, activeMember(groupId, actorId));
        if (query.cursor() != null && occurrences.findById(query.cursor())
                .filter(item -> item.recurringSettlementId().equals(id)).isEmpty()) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_CURSOR);
        }
        var values = occurrences.findByRecurringSettlementId(id, query.cursor(), query.limit() + 1);
        boolean more = values.size() > query.limit();
        var page = values.subList(0, Math.min(values.size(), query.limit()));
        return new RecurringSettlementPageResult<>(page.stream().map(RecurringSettlementOccurrenceResult::from).toList(),
                more ? page.getLast().id() : null);
    }

    @Override
    @Transactional
    public RecurringSettlementResult cancel(Long groupId, UUID id) {
        var value = recurring.findByIdForUpdate(id).filter(item -> item.groupId().equals(groupId) && item.deletedAt() == null)
                .orElseThrow(() -> ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_NOT_FOUND));
        access.requireCreator(value, currentActorPort.getCurrentActor().userId());
        var cancelled = value.cancel(RecurringSettlement.CancellationReason.USER_CANCELLED, LocalDateTime.now(clock));
        saveRecurring.update(cancelled);
        return presentation.assemble(cancelled);
    }

    private RecurringSettlement load(Long groupId, UUID id) {
        return recurring.findById(id).filter(value -> value.groupId().equals(groupId) && value.deletedAt() == null)
                .orElseThrow(() -> ApplicationException.of(RecurringSettlementStatusCode.RECURRING_SETTLEMENT_NOT_FOUND));
    }

    private boolean activeMember(Long groupId, Long actorId) {
        return groups.findById(groupId).filter(group -> group.status() == GroupStatus.ACTIVE).isPresent()
                && members.findByGroupIdAndUserId(groupId, actorId).filter(member -> member.isActive()).isPresent();
    }
}
