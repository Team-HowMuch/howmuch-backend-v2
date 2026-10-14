package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 선택 대기 품목 정산의 받을 금액을 계산할 재료를 여러 정산에 대해 한 번에 불러온다.
 *
 * <p>정산 목록과 AI 비서는 여러 정산을 한꺼번에 보여 주므로 정산마다 조회하면 건수만큼 쿼리가 늘어난다.
 */
public interface LoadRequestedItemReceivableFactsPort {
    /**
     * @return 삭제되었거나 없는 정산은 결과에서 빠진다
     */
    List<RequestedItemReceivableFacts> findAllBySettlementIds(Collection<UUID> settlementIds);

    /**
     * @param creatorParticipantId 생성자가 참여자에 없으면 null
     */
    record RequestedItemReceivableFacts(
            UUID settlementId,
            long totalAmount,
            UUID creatorParticipantId,
            List<SettlementItem> items,
            List<SettlementItemSelection> selections,
            List<SettlementExpense> expenses
    ) {
        public RequestedItemReceivableFacts {
            items = List.copyOf(items);
            selections = List.copyOf(selections);
            expenses = List.copyOf(expenses);
        }
    }
}
