package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListPageResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementListService implements GetSettlementsUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementListPort loadSettlementListPort;

    @Override
    @Transactional(readOnly = true)
    public SettlementListPageResult get(GetSettlementsCommand command) {
        Long userId = currentActorPort.getCurrentActor().userId();
        SettlementListCursor cursor = SettlementListCursor.decode(command.cursor());
        var fetched = loadSettlementListPort.findPage(
                userId, command.tab(), command.status(), command.groupId(), command.yearMonth(),
                cursor == null ? null : cursor.requestedAt(),
                cursor == null ? null : cursor.id(), command.size() + 1
        );
        boolean hasNext = fetched.size() > command.size();
        var page = fetched.subList(0, Math.min(command.size(), fetched.size()));
        String nextCursor = hasNext
                ? new SettlementListCursor(page.getLast().requestedAt(), page.getLast().settlementId()).encode()
                : null;
        var items = page.stream()
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
        return new SettlementListPageResult(items, nextCursor, hasNext);
    }
}
