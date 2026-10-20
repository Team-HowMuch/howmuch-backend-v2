package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.group.service.GroupAccessService;
import com.amateur.howmuchbackend.application.port.in.settlement.GetGroupSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetGroupSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.GroupSettlementListItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.GroupSettlementListItemResult.ViewerRole;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadGroupSettlementListPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadGroupSettlementListPort.GroupSettlementListReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GroupSettlementListService implements GetGroupSettlementsUseCase {
    private final CurrentActorPort currentActorPort;
    private final GroupAccessService groupAccess;
    private final LoadGroupSettlementListPort groupListPort;
    private final RequestedItemReceivableCalculator receivableCalculator;
    private final SettlementReminderCooldownReader cooldownReader;
    private final SettlementCategoryCatalog categoryCatalog;

    @Override
    @Transactional(readOnly = true)
    public List<GroupSettlementListItemResult> get(GetGroupSettlementsCommand command) {
        Long viewerId = currentActorPort.getCurrentActor().userId();
        groupAccess.loadActive(command.groupId());
        groupAccess.requireActiveMember(command.groupId(), viewerId);
        var items = groupListPort.findAllInGroup(command.groupId(), viewerId, command.status());
        if (items.isEmpty()) {
            return List.of();
        }
        var requesterItems = items.stream().filter(item -> item.requesterId().equals(viewerId)).toList();
        var receivables = receivableCalculator.calculate(requesterItems.stream()
                .filter(item -> receivableCalculator.appliesTo(
                        item.summary().allocationType(), item.summary().status()))
                .map(item -> item.summary().settlementId()).toList());
        var reminderTimes = cooldownReader.findLatestNextAvailableAt(requesterItems.stream()
                .collect(Collectors.toMap(item -> item.summary().settlementId(), item -> item.summary().status())));
        Map<String, SettlementCategoryResult> categories = categoryCatalog.byCode();
        SettlementCategoryResult uncategorized = categories.getOrDefault(SettlementCategory.UNCATEGORIZED,
                new SettlementCategoryResult(SettlementCategory.UNCATEGORIZED, "미분류", null, "uncategorized"));
        return items.stream().map(item -> {
            var summary = item.summary();
            ViewerRole role = item.requesterId().equals(viewerId) ? ViewerRole.REQUESTER
                    : item.viewerParticipant() ? ViewerRole.PARTICIPANT : ViewerRole.NONE;
            return new GroupSettlementListItemResult(
                    summary.settlementId(), role, item.requesterName(), item.requesterPublicId(),
                    summary.status(), summary.requestedAt(), summary.endedAt(), summary.completedAt(),
                    summary.allocationType(), summary.totalAmount(), summary.participantCount(),
                    summary.payableParticipantCount(), summary.completedParticipantCount(), summary.storeName(),
                    item.viewerCategoryCode() == null ? uncategorized
                            : categories.getOrDefault(item.viewerCategoryCode(), uncategorized),
                    role == ViewerRole.REQUESTER
                            ? receivables.getOrDefault(summary.settlementId(), summary.receivableShareAmount()) : null,
                    role == ViewerRole.PARTICIPANT ? amountToPay(item) : null,
                    role == ViewerRole.REQUESTER ? reminderTimes.get(summary.settlementId()) : null);
        }).toList();
    }

    private Long amountToPay(GroupSettlementListReadModel item) {
        var summary = item.summary();
        if (summary.amountToPay() != null) {
            return summary.amountToPay();
        }
        if (summary.allocationType() == SettlementAllocationType.DUTCH_PAY
                && (summary.status() == SettlementStatus.AWAITING_DEPOSIT
                || summary.status() == SettlementStatus.COMPLETED
                || summary.status() == SettlementStatus.NETTING_IN_PROGRESS)) {
            return 0L;
        }
        return receivableCalculator.appliesTo(summary.allocationType(), summary.status())
                ? summary.totalAmount() : null;
    }
}
