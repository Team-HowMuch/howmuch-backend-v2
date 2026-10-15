package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementListPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementListReadModel;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementListService implements GetSettlementsUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementListPort loadSettlementListPort;
    private final RequestedItemReceivableCalculator requestedItemReceivableCalculator;
    private final MySettlementCategoryReader myCategoryReader;

    @Override
    public List<SettlementListItemResult> get(GetSettlementsCommand command) {
        Long userId = currentActorPort.getCurrentActor().userId();
        List<SettlementListReadModel> items = loadSettlementListPort.findAll(
                userId, command.tab(), command.status(), command.groupId(), command.yearMonth()
        );
        Map<UUID, Long> requestedReceivables = command.tab() == SettlementListTab.RECEIVE
                ? requestedItemReceivableCalculator.calculate(items.stream()
                        .filter(item -> requestedItemReceivableCalculator.appliesTo(
                                item.allocationType(), item.status()))
                        .map(SettlementListReadModel::settlementId)
                        .toList())
                : Map.of();
        Map<UUID, SettlementCategoryResult> myCategories = myCategoryReader.read(
                userId, items.stream().map(SettlementListReadModel::settlementId).toList());
        return items.stream()
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
                        receivableAmount(item, command.tab(), requestedReceivables),
                        item.requesterName(),
                        item.groups().stream()
                                .map(group -> new SettlementListItemResult.GroupSummary(
                                        group.groupId(),
                                        group.groupName(),
                                        group.imageVersion()
                                ))
                                .toList(),
                        myCategories.get(item.settlementId())
                ))
                .toList();
    }

    /**
     * 생성자가 받을 금액. 정산 상세의 {@code summary.expectedAmount}와 같은 기준이다.
     * 선택 대기 품목 정산은 다시 계산한 값을, 그 밖에는 생성자를 뺀 share 합계를 쓴다.
     * 다시 계산하지 못한 정산도 share 합계로 되돌린다.
     */
    private Long receivableAmount(
            SettlementListReadModel item,
            SettlementListTab tab,
            Map<UUID, Long> requestedReceivables
    ) {
        if (tab != SettlementListTab.RECEIVE) {
            return null;
        }
        return requestedReceivables.getOrDefault(item.settlementId(), item.receivableShareAmount());
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
