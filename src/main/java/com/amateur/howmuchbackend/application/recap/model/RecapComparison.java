package com.amateur.howmuchbackend.application.recap.model;

import java.math.BigDecimal;

public record RecapComparison(Status status, Long previousOwnAmount,
                              Long amountDifference, BigDecimal changeRatePercent) {
    public enum Status { NO_PREVIOUS, PREVIOUS_ZERO, UNCHANGED, INCREASED, DECREASED }
}
