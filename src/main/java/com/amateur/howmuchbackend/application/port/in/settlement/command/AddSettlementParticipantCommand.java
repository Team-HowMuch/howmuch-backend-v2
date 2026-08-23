package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;

public record AddSettlementParticipantCommand(
        SettlementParticipantSource source,
        Long groupId,
        String publicId,
        String displayName,
        String phoneNumber
) {
}
