package com.amateur.howmuchbackend.application.port.in.recap.result;

import java.time.YearMonth;
import java.util.List;

public record RecapMonthsResult(List<YearMonth> months) {
    public RecapMonthsResult { months = List.copyOf(months); }
}
