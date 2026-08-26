package com.amateur.howmuchbackend.domain.treasurer;

import java.util.List;

public record TreasurerSummary(
        long sendAmount,
        int sendCount,
        long receiveAmount,
        int receiveCount,
        int inProgressCount,
        int completedCount,
        String message
) {
    public static TreasurerSummary from(List<PersonalSettlement> settlements) {
        long sendAmount = 0;
        int sendCount = 0;
        long receiveAmount = 0;
        int receiveCount = 0;
        int inProgressCount = 0;
        int completedCount = 0;

        for (PersonalSettlement settlement : settlements) {
            if (settlement.isOpen()) {
                inProgressCount++;
                if (settlement.isSend()) {
                    sendCount++;
                    sendAmount += settlement.amountOrZero();
                } else {
                    receiveCount++;
                    receiveAmount += settlement.amountOrZero();
                }
            } else {
                completedCount++;
            }
        }

        return new TreasurerSummary(
                sendAmount,
                sendCount,
                receiveAmount,
                receiveCount,
                inProgressCount,
                completedCount,
                guidance(sendCount, sendAmount, receiveCount, receiveAmount, inProgressCount)
        );
    }

    private static String guidance(
            int sendCount,
            long sendAmount,
            int receiveCount,
            long receiveAmount,
            int inProgressCount
    ) {
        if (sendCount > 0) {
            return "보낼 정산 %d건, %,d원이 남아 있어요.".formatted(sendCount, sendAmount);
        }
        if (receiveCount > 0) {
            return "받을 정산 %d건, %,d원이 있어요.".formatted(receiveCount, receiveAmount);
        }
        if (inProgressCount > 0) {
            return "진행 중인 정산 %d건이 있어요.".formatted(inProgressCount);
        }
        return "현재 처리할 정산이 없어요.";
    }
}
