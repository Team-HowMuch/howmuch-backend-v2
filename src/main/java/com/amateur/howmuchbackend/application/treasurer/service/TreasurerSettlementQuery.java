package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class TreasurerSettlementQuery {
    private final CurrentActorPort currentActorPort;
    private final LoadTreasurerSettlementsPort loadTreasurerSettlementsPort;
    private final TreasurerRequestedReceivableResolver requestedReceivableResolver;

    public List<PersonalSettlement> loadMine() {
        Long userId = currentActorPort.getCurrentActor().userId();
        return loadTreasurerSettlementsPort.findAll(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    private PersonalSettlement toDomain(LoadTreasurerSettlementsPort.TreasurerSettlementRecord record) {
        return new PersonalSettlement(
                record.settlementId(),
                record.title(),
                record.direction(),
                record.status(),
                record.requestedAt(),
                amountOf(record),
                record.counterpartName(),
                record.payerCount(),
                record.completedPayerCount(),
                record.allocationType()
        );
    }

    /**
     * 품목 선택이 끝나기 전의 받을 정산은 share가 없어 조회 금액이 0원이다.
     * 정산 상세와 같은 기준(영수증 총액 − 생성자 몫)으로 바꿔 쓴다.
     */
    private Long amountOf(LoadTreasurerSettlementsPort.TreasurerSettlementRecord record) {
        if (!requestedReceivableResolver.appliesTo(record)) {
            return record.amount();
        }
        return requestedReceivableResolver.resolve(record.settlementId()).orElse(record.amount());
    }
}
