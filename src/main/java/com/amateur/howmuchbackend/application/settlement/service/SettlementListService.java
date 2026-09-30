package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementListService implements GetSettlementsUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementListPort loadSettlementListPort;

    @Override
    public List<SettlementListItemResult> get(GetSettlementsCommand command) {
        Long userId = currentActorPort.getCurrentActor().userId();
        return loadSettlementListPort.findAll(
                        userId, command.tab(), command.status(), command.groupId(), command.yearMonth()
                ).stream()
                .map(item -> new SettlementListItemResult(
                        item.settlementId(),
                        item.status(),
                        item.requestedAt(),
                        item.endedAt(),
                        item.completedAt(),
                        command.tab() == SettlementListTab.RECEIVE ? item.totalAmount() : null,
                        item.allocationType(),
                        item.participantCount(),
                        item.payableParticipantCount(),
                        item.completedParticipantCount(),
                        amountToPay(item, command.tab()),
                        item.requesterName(),
                        item.groups().stream()
                                .map(group -> new SettlementListItemResult.GroupSummary(
                                        group.groupId(),
                                        group.groupName(),
                                        group.imageVersion()
                                ))
                                .toList()
                ))
                .toList();
    }

    private Long amountToPay(SettlementListReadModel item, SettlementListTab tab) {
        if (tab != SettlementListTab.PAY) {
            return null;
        }
        if (item.amountToPay() == null
                && item.allocationType() == SettlementAllocationType.ITEM_ALLOCATION
                && (item.status() == SettlementStatus.REQUESTED
                || item.status() == SettlementStatus.SUSPENDED)) {
            return item.totalAmount();
        }
        return item.amountToPay();
    }
}
