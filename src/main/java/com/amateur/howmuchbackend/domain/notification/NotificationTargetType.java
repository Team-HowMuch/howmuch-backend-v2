package com.amateur.howmuchbackend.domain.notification;

public enum NotificationTargetType {
    GROUP_SETTLEMENT_NETTING,
    SETTLEMENT_PARTICIPATION,
    SETTLEMENT_DETAIL,
    GROUP_INVITATION,
    GROUP_DETAIL,
    MONTHLY_RECAP,
    FINANCE_AUTHENTICATION;

    public boolean isSettlement() {
        return this == SETTLEMENT_PARTICIPATION || this == SETTLEMENT_DETAIL;
    }
}
