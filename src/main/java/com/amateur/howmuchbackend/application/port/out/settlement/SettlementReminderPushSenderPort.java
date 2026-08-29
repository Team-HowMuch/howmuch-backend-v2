package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.UUID;

public interface SettlementReminderPushSenderPort {
    SendResult send(String firebaseInstallationId, UUID settlementId);

    enum SendResult {
        SENT,
        INVALID_TARGET
    }
}
