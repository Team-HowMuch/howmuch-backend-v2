package com.amateur.howmuchbackend.application.port.in.settlement.result;

import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.user.User;

import java.util.UUID;

public record SettlementParticipantListItemResult(
        UUID participantId,
        SettlementParticipantSource source,
        String publicId,
        String displayName,
        int displayOrder,
        boolean hasAvatar
) {
    public static SettlementParticipantListItemResult of(
            SettlementParticipant participant,
            User linkedUser
    ) {
        return new SettlementParticipantListItemResult(
                participant.id(),
                participant.source(),
                linkedUser == null ? null : linkedUser.publicId(),
                participant.displayName(),
                participant.displayOrder(),
                linkedUser != null && linkedUser.avatarFileName() != null
        );
    }
}
