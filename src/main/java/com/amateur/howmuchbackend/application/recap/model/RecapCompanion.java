package com.amateur.howmuchbackend.application.recap.model;

public record RecapCompanion(Kind kind, String identityKey, String displayName, String shareDisplayName,
                             String avatarFileName, int completedSettlementCount, long totalOwnAmount) {
    public enum Kind { MEMBER, GUEST, WITHDRAWN_MEMBER }

    public RecapCompanion withAvatarFileName(String fileName) {
        return new RecapCompanion(kind, identityKey, displayName, shareDisplayName,
                fileName, completedSettlementCount, totalOwnAmount);
    }
}
