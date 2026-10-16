package com.amateur.howmuchbackend.application.settlement.netting.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.LoadNettingNotificationStatePort.State;
import com.amateur.howmuchbackend.domain.notification.NotificationType;

import org.springframework.stereotype.Component;

@Component
public class NettingNotificationEligibilityPolicy {
    public boolean available(NotificationType type, State state) {
        if (!state.visible()) return false;
        return switch (type) {
            case GROUP_NETTING_CONFIRMED ->
                    "ACTIVE".equals(state.status()) || "COMPLETED".equals(state.status());
            case GROUP_NETTING_COMPLETED -> "COMPLETED".equals(state.status());
            case NETTED_TRANSFER_REMINDER ->
                    "ACTIVE".equals(state.status())
                            && !state.processing()
                            && state.openReviews() == 0
                            && state.transferConfirmed() != null
                            && state.transferConfirmed() == 0
                            && state.transferRemaining() != null
                            && state.transferRemaining() > 0;
            default -> false;
        };
    }
}
