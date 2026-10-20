package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import java.util.Optional;

public interface NettingEstimateCachePort {
    record Counts(
            int eligibleSettlementCount,
            int beforeTransferCount,
            int afterTransferCount,
            int compressionTargetCount) {
        public Counts {
            if (compressionTargetCount < eligibleSettlementCount
                    || (long) compressionTargetCount > (long) eligibleSettlementCount + 1
                    || eligibleSettlementCount < 0
                    || beforeTransferCount < 0
                    || afterTransferCount < 0
                    || afterTransferCount > beforeTransferCount)
                throw new IllegalArgumentException("Invalid estimate counts");
        }

        public int reducedTransferCount() {
            return Math.subtractExact(beforeTransferCount, afterTransferCount);
        }
    }

    record Claim(String token, String revision) {}

    Optional<Counts> find(long groupId);

    Optional<Claim> tryClaim(long groupId);

    boolean publish(long groupId, Claim claim, Counts counts);

    void release(long groupId, Claim claim);

    void invalidate(long groupId);
}
