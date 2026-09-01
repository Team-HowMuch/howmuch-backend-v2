package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementDutchPayAllocationsCommand.Allocation;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class DutchPayAllocationPolicy {
    private static final int MIN_PARTICIPANT_COUNT = 2;

    public List<SettlementDutchPayAllocation> calculate(
            Settlement settlement,
            List<SettlementParticipant> participants,
            long totalAmount,
            LocalDateTime now
    ) {
        if (participants == null || participants.size() < MIN_PARTICIPANT_COUNT) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_PARTICIPANTS_INSUFFICIENT
            );
        }
        SettlementParticipant creator = participants.stream()
                .filter(participant -> participant.isLinkedTo(settlement.creatorId()))
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_CREATOR_PARTICIPANT_NOT_FOUND
                ));

        long baseAmount = totalAmount / participants.size();
        long remainder = totalAmount % participants.size();
        return participants.stream()
                .map(participant -> SettlementDutchPayAllocation.create(
                        settlement.id(),
                        participant.id(),
                        participant.id().equals(creator.id())
                                ? Math.addExact(baseAmount, remainder)
                                : baseAmount,
                        now
                ))
                .toList();
    }

    /**
     * 일괄 저장 요청이 참여자 전원을 정확히 한 번씩 덮고 합계가 정산 총액과 같은지 확인한다.
     * 검증을 통과하면 참여자 ID로 금액을 찾을 수 있는 Map을 돌려준다.
     */
    public Map<UUID, Long> validateReplacement(
            Settlement settlement,
            List<SettlementParticipant> participants,
            List<Allocation> allocations
    ) {
        if (participants == null || participants.size() < MIN_PARTICIPANT_COUNT) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_PARTICIPANTS_INSUFFICIENT
            );
        }
        if (allocations == null || allocations.size() != participants.size()) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_DUTCH_PAY_ALLOCATION_REQUEST
            );
        }

        Set<UUID> participantIds = participants.stream()
                .map(SettlementParticipant::id)
                .collect(Collectors.toUnmodifiableSet());
        Map<UUID, Long> amountByParticipantId = new HashMap<>();
        long allocatedAmount = 0L;
        try {
            for (Allocation allocation : allocations) {
                if (allocation == null
                        || allocation.participantId() == null
                        || !participantIds.contains(allocation.participantId())
                        || amountByParticipantId.containsKey(allocation.participantId())) {
                    throw ApplicationException.of(
                            SettlementStatusCode.INVALID_DUTCH_PAY_ALLOCATION_REQUEST
                    );
                }
                amountByParticipantId.put(allocation.participantId(), allocation.amount());
                allocatedAmount = Math.addExact(allocatedAmount, allocation.amount());
            }
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_DUTCH_PAY_TOTAL,
                    exception
            );
        }
        if (allocatedAmount != settlement.totalAmount()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DUTCH_PAY_TOTAL_MISMATCH
            );
        }
        return Map.copyOf(amountByParticipantId);
    }
}
