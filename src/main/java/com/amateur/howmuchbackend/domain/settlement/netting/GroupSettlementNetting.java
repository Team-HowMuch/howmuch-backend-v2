package com.amateur.howmuchbackend.domain.settlement.netting;

import java.time.LocalDateTime;
import java.util.*;

public record GroupSettlementNetting(
        UUID id, long groupId, long version, Status status, LocalDateTime createdAt) {
    public enum Status {
        BUILDING,
        ACTIVE,
        SUPERSEDED,
        COMPLETED
    }

    public GroupSettlementNetting {
        if (id == null || groupId <= 0 || version <= 0 || status == null || createdAt == null)
            throw new IllegalArgumentException("Invalid group netting");
    }

    public static GroupSettlementNetting create(
            UUID id, long groupId, long version, LocalDateTime now) {
        return new GroupSettlementNetting(id, groupId, version, Status.ACTIVE, now);
    }

    public Optional<GroupSettlementNetting> completeIfPaid(
            boolean allTransfersPaid, boolean hasOpenReview) {
        if (status != Status.ACTIVE || !allTransfersPaid || hasOpenReview) return Optional.empty();
        return Optional.of(
                new GroupSettlementNetting(id, groupId, version, Status.COMPLETED, createdAt));
    }
}
