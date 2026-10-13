package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.RecapComparison;
import java.math.BigDecimal;

public record RecapComparisonResult(String status, Long previousOwnAmount, Long amountDifference, BigDecimal changeRatePercent) {
    public static RecapComparisonResult from(RecapComparison comparison) {
        return new RecapComparisonResult(comparison.status().name(), comparison.previousOwnAmount(),
                comparison.amountDifference(), comparison.changeRatePercent());
    }
}
