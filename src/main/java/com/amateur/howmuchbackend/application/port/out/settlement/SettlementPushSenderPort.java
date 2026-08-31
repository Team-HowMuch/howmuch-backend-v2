package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.UUID;

public interface SettlementPushSenderPort {
    SendResult sendParticipationRequest(
            String firebaseInstallationId,
            UUID settlementId,
            String shareLink
    );

    SendResult sendReminder(String firebaseInstallationId, UUID settlementId);

    enum SendResult {
        SENT,
        INVALID_TARGET
    }
}
