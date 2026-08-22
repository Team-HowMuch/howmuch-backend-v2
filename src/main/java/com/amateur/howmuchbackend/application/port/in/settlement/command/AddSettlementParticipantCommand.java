package com.amateur.howmuchbackend.application.port.in.settlement.command;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;

public record AddSettlementParticipantCommand(
        SettlementParticipantSource source,
        String publicId,
        String displayName,
        String phoneNumber
) {
}
