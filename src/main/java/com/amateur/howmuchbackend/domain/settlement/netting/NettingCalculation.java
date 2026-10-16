package com.amateur.howmuchbackend.domain.settlement.netting;

import java.util.List;

public record NettingCalculation(
        String algorithm, int originalTransferCount, List<NettingPayment> payments) {
    public NettingCalculation {
        payments = List.copyOf(payments);
    }
}
