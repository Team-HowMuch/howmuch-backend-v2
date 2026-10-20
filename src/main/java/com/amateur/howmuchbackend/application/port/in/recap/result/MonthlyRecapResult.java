package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.MonthlyRecap;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

public record MonthlyRecapResult(YearMonth yearMonth, String ownerName, LocalDateTime generatedAt,
        int completedSettlementCount, long totalOwnAmount, RecapComparisonResult comparison, RecapCompanionResult companion,
        List<RecapCategoryTotalResult> categories, RecapRecordResult record, List<RecapBenefitResult> benefits) {
    public static MonthlyRecapResult from(MonthlyRecap recap) {
        return new MonthlyRecapResult(recap.yearMonth(), recap.ownerName(), recap.generatedAt(),
                recap.summary().completedSettlementCount(), recap.summary().totalOwnAmount(),
                RecapComparisonResult.from(recap.comparison()), RecapCompanionResult.from(recap.companion(), false),
                recap.categories() == null ? null : recap.categories().stream().map(RecapCategoryTotalResult::from).toList(),
                RecapRecordResult.from(recap.record()),
                recap.benefits() == null ? null : recap.benefits().stream().map(RecapBenefitResult::from).toList());
    }
}
