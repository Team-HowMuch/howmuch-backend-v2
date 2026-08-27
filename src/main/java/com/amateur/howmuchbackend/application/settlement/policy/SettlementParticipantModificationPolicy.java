package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

@Component
public class SettlementParticipantModificationPolicy {
    public void validateAddition(boolean alreadyExists) {
        if (alreadyExists) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_PARTICIPANT_ALREADY_EXISTS);
        }
    }

    public void validateRemoval(Settlement settlement, SettlementParticipant participant) {
        if (participant.isLinkedTo(settlement.creatorId())) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_CREATOR_PARTICIPANT_REQUIRED);
        }
    }
}
