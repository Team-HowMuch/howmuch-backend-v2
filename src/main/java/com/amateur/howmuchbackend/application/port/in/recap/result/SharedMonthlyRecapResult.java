package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import java.time.LocalDateTime;
import java.time.YearMonth;

public record SharedMonthlyRecapResult(YearMonth yearMonth, LocalDateTime generatedAt,
        int completedSettlementCount, long totalOwnAmount, RecapComparisonResult comparison, RecapCompanionResult companion) {
    public static SharedMonthlyRecapResult from(MonthlyRecap recap) {
        return new SharedMonthlyRecapResult(recap.yearMonth(), recap.generatedAt(),
                recap.summary().completedSettlementCount(), recap.summary().totalOwnAmount(),
                RecapComparisonResult.from(recap.comparison()), RecapCompanionResult.from(recap.companion(), true));
    }
}
