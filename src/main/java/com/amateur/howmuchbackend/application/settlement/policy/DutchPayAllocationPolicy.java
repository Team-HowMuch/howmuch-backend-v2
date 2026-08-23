package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

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
}
