package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.util.*;

public interface LoadNettingNotificationStatePort {
    record State(
            boolean visible,
            String status,
            int openReviews,
            Long transferConfirmed,
            Long transferRemaining,
            boolean processing) {}

    Optional<State> find(UUID nettingId, long userId, UUID transferId);
}
