package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort.TreasurerSettlementRecord;
import com.amateur.howmuchbackend.application.settlement.service.RequestedItemReceivableCalculator;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TreasurerSettlementQuery {
    private final CurrentActorPort currentActorPort;
    private final LoadTreasurerSettlementsPort loadTreasurerSettlementsPort;
    private final RequestedItemReceivableCalculator requestedItemReceivableCalculator;

    public List<PersonalSettlement> loadMine() {
        Long userId = currentActorPort.getCurrentActor().userId();
        List<TreasurerSettlementRecord> records = loadTreasurerSettlementsPort.findAll(userId);
        Map<UUID, Long> requestedReceivables = requestedItemReceivableCalculator.calculate(records.stream()
                .filter(this::usesRequestedReceivable)
                .map(TreasurerSettlementRecord::settlementId)
                .toList());
        return records.stream()
                .map(record -> toDomain(record, requestedReceivables))
                .toList();
    }

    private PersonalSettlement toDomain(TreasurerSettlementRecord record, Map<UUID, Long> requestedReceivables) {
        return new PersonalSettlement(
                record.settlementId(),
                record.title(),
                record.direction(),
                record.status(),
                record.requestedAt(),
                amountOf(record, requestedReceivables),
                record.counterpartName(),
                record.payerCount(),
                record.completedPayerCount(),
                record.allocationType()
        );
    }

    /**
     * 품목 선택이 끝나기 전({@code REQUESTED}, {@code SUSPENDED})의 받을 정산은 share가 비었거나 일부만 있어
     * 조회 금액이 실제보다 작다. 정산 상세·정산 목록과 같은 기준(영수증 총액 − 생성자 몫)으로 바꿔 쓴다.
     * 계산하지 못한 정산은 조회 금액을 그대로 쓴다.
     */
    private Long amountOf(TreasurerSettlementRecord record, Map<UUID, Long> requestedReceivables) {
        if (!usesRequestedReceivable(record)) {
            return record.amount();
        }
        return requestedReceivables.getOrDefault(record.settlementId(), record.amount());
    }

    private boolean usesRequestedReceivable(TreasurerSettlementRecord record) {
        return record.direction() == SettlementDirection.RECEIVE
                && requestedItemReceivableCalculator.appliesTo(record.allocationType(), record.status());
    }
}
