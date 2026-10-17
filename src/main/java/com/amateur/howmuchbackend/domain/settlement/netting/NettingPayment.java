package com.amateur.howmuchbackend.domain.settlement.netting;

public record NettingPayment(long payerId, long receiverId, long amount) {
    public NettingPayment {
        if (payerId <= 0 || receiverId <= 0 || payerId == receiverId || amount <= 0) {
            throw new IllegalArgumentException("Invalid final payment");
        }
    }
}
