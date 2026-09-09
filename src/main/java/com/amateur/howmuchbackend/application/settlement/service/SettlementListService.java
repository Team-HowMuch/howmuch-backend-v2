package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
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
        return loadSettlementListPort.findAll(userId, command.tab(), command.status(), command.groupId()).stream()
                .map(item -> new SettlementListItemResult(
                        item.settlementId(),
                        item.status(),
                        item.requestedAt(),
                        item.completedAt(),
                        item.totalAmount(),
                        item.allocationType(),
                        item.participantCount(),
                        item.payableParticipantCount(),
                        item.completedParticipantCount(),
                        item.amountToPay(),
                        item.requesterName(),
                        item.groups().stream()
                                .map(group -> new SettlementListItemResult.GroupSummary(
                                        group.groupId(),
                                        group.groupName(),
                                        group.hasImage()
                                ))
                                .toList()
                ))
                .toList();
    }
}
