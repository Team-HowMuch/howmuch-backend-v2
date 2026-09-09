package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class TreasurerSettlementPersistenceAdapter implements LoadTreasurerSettlementsPort {
    private static final String SQL_RECEIVE_TAB = "RECEIVE";
    private static final String SQL_PAY_TAB = "PAY";

    private final SettlementJpaRepository settlementJpaRepository;

    @Override
    public List<TreasurerSettlementRecord> findAll(Long userId) {
        List<TreasurerSettlementRecord> records = new ArrayList<>();
        records.addAll(map(settlementJpaRepository.findAllForTreasurer(userId, SQL_RECEIVE_TAB), SettlementDirection.RECEIVE));
        records.addAll(map(settlementJpaRepository.findAllForTreasurer(userId, SQL_PAY_TAB), SettlementDirection.SEND));
        return List.copyOf(records);
    }

    private SettlementAllocationType allocationTypeOf(String allocationType) {
        return allocationType == null ? null : SettlementAllocationType.valueOf(allocationType);
    }

    private List<TreasurerSettlementRecord> map(
            List<SettlementJpaRepository.TreasurerSettlementProjection> projections,
            SettlementDirection direction
    ) {
        return projections.stream()
                .map(projection -> new TreasurerSettlementRecord(
                        projection.getSettlementId(),
                        projection.getTitle(),
                        direction,
                        SettlementStatus.valueOf(projection.getStatus()),
                        projection.getRequestedAt(),
                        // Long.valueOf 없이 두면 Long/long 혼합 삼항연산자가 long으로 승격돼
                        // amountToPay(null 가능)를 언박싱하다 NPE가 난다.
                        direction == SettlementDirection.SEND
                                ? projection.getAmountToPay()
                                : Long.valueOf(projection.getReceivableAmount()),
                        projection.getRequesterName(),
                        Math.toIntExact(projection.getPayerCount()),
                        Math.toIntExact(projection.getCompletedPayerCount()),
                        allocationTypeOf(projection.getAllocationType())
                ))
                .toList();
    }
}
