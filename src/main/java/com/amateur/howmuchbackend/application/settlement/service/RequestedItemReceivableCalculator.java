package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadRequestedItemReceivableFactsPort.RequestedItemReceivableFacts;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 선택 대기 품목 정산의 받을 금액(영수증 총액 − 생성자 몫)을 여러 정산에 대해 한 번에 계산한다.
 *
 * <p>정산 목록과 AI 비서가 같이 쓴다. 정산 상세는 한 건이라 같은 Policy 메서드를 직접 부른다.
 * 계산 재료는 Port 한 번 호출로 불러와 정산 건수만큼 쿼리가 늘지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RequestedItemReceivableCalculator {
    private final LoadRequestedItemReceivableFactsPort loadRequestedItemReceivableFactsPort;
    private final SettlementItemAllocationPolicy itemAllocationPolicy;

    public boolean appliesTo(SettlementAllocationType allocationType, SettlementStatus status) {
        return itemAllocationPolicy.usesRequestedReceivable(allocationType, status);
    }

    /**
     * @return 정산 ID별 받을 금액. 정산이 없거나 저장된 값이 서로 맞지 않아 계산할 수 없는 정산은 빠진다.
     *         호출자는 빠진 정산에 기존 share 합계를 쓴다.
     */
    public Map<UUID, Long> calculate(Collection<UUID> settlementIds) {
        if (settlementIds == null || settlementIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> amounts = new HashMap<>();
        for (RequestedItemReceivableFacts facts
                : loadRequestedItemReceivableFactsPort.findAllBySettlementIds(settlementIds)) {
            try {
                amounts.put(facts.settlementId(), receivable(facts));
            } catch (ApplicationException | ArithmeticException exception) {
                // 저장된 데이터가 서로 맞지 않는 정산 한 건 때문에 목록이나 AI 비서 전체가 실패하지 않게 한다.
                log.warn(
                        "Failed to calculate requested item receivable. settlementId={} failureType={}",
                        facts.settlementId(),
                        exception.getClass().getSimpleName()
                );
            }
        }
        return amounts;
    }

    private long receivable(RequestedItemReceivableFacts facts) {
        if (facts.creatorParticipantId() == null) {
            return facts.totalAmount();
        }
        long receiptDiscount = facts.expenses().stream()
                .mapToLong(SettlementExpense::discount)
                .reduce(0L, Math::addExact);
        long deliveryFee = facts.expenses().stream()
                .mapToLong(SettlementExpense::deliveryFee)
                .reduce(0L, Math::addExact);
        return itemAllocationPolicy.calculateRequestedReceivableAmount(
                facts.totalAmount(),
                facts.items(),
                facts.selections(),
                facts.creatorParticipantId(),
                receiptDiscount,
                deliveryFee
        );
    }
}
