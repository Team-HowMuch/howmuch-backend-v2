package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record MonthlyRecapResult(YearMonth yearMonth, String ownerName, LocalDateTime generatedAt,
        int completedSettlementCount, long totalOwnAmount, RecapComparisonResult comparison, RecapCompanionResult companion) {
    public static MonthlyRecapResult from(MonthlyRecap recap) {
        return new MonthlyRecapResult(recap.yearMonth(), recap.ownerName(), recap.generatedAt(),
                recap.summary().completedSettlementCount(), recap.summary().totalOwnAmount(),
                RecapComparisonResult.from(recap.comparison()), RecapCompanionResult.from(recap.companion(), false));
    }
}
