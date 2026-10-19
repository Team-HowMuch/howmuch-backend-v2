package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 원본 총액과 현재 사용자의 Share 금액을 제공한다. 목록 표시 금액은 Application에서 결정한다.
 *
 * <p>{@code receivableShareAmount}는 RECEIVE 탭에서 생성자를 뺀 참여자 share 합계다. 선택 대기 품목 정산은
 * share가 비었거나 일부만 있어 Application이 다시 계산한다. PAY 탭에서는 null이다.
 */
public record SettlementListReadModel(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        LocalDateTime endedAt,
        LocalDateTime completedAt,
        Long totalAmount,
        SettlementAllocationType allocationType,
        Integer participantCount,
        Integer payableParticipantCount,
        Integer completedParticipantCount,
        Long amountToPay,
        Long receivableShareAmount,
        String requesterName,
        String storeName,
        List<GroupSummary> groups
) {
    public record GroupSummary(
            Long groupId,
            String groupName,
            String imageVersion
    ) {
    }
}
