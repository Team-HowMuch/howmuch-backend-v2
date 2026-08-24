package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;

import java.time.LocalDateTime;
import java.util.UUID;

public interface IssueSettlementParticipantContextPort {
    SettlementParticipantContext issue(
            String linkTokenHash,
            UUID settlementId,
            UUID participantId,
            LocalDateTime now
    );
}
