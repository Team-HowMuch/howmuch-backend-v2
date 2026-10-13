package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.RecapCompanion;

public record RecapCompanionResult(String kind, String displayName, boolean hasAvatar,
                                   int completedSettlementCount, long totalOwnAmount) {
    public static RecapCompanionResult from(RecapCompanion c, boolean shared) {
        return c == null ? null : new RecapCompanionResult(c.kind().name(),
                shared ? c.shareDisplayName() : c.displayName(), c.avatarFileName() != null,
                c.completedSettlementCount(), c.totalOwnAmount());
    }
}
