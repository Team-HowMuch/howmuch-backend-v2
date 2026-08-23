package com.amateur.howmuchbackend.application.settlement.policy;

public record SettlementContactMatchUsage(
        long requestCount,
        long contactCount
) {
    public SettlementContactMatchUsage {
        if (requestCount < 1 || contactCount < 1) {
            throw new IllegalArgumentException("연락처 매칭 사용량은 1 이상이어야 합니다.");
        }
    }
}
