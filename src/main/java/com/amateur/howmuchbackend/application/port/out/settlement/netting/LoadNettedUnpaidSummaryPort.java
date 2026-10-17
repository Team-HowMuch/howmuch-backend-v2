package com.amateur.howmuchbackend.application.port.out.settlement.netting;

public interface LoadNettedUnpaidSummaryPort {
    record Summary(long remainingReceivableAmount, int unpaidTransferCount) {}

    Summary findByReceiver(long receiverId);
}
