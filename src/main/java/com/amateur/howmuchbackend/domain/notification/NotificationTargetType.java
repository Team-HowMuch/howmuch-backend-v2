package com.amateur.howmuchbackend.domain.notification;

public enum NotificationTargetType {
    SETTLEMENT_PARTICIPATION,
    SETTLEMENT_DETAIL,
    GROUP_INVITATION,
    GROUP_DETAIL,
    MONTHLY_RECAP;

    public boolean isSettlement() {
        return this == SETTLEMENT_PARTICIPATION || this == SETTLEMENT_DETAIL;
    }
}
