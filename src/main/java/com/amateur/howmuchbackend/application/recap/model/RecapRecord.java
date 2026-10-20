package com.amateur.howmuchbackend.application.recap.model;

import java.time.DayOfWeek;

public record RecapRecord(BusiestWeekday busiestWeekday, FrequentCategory frequentCategory,
                          LargestSettlement largestSettlement, FastestSettlement fastestSettlement) {
    public record BusiestWeekday(DayOfWeek weekday, int count) {}
    public record FrequentCategory(RecapCategory category, int count) {}
    public record LargestSettlement(long totalOwnAmount, RecapCategory category) {}
    public record FastestSettlement(long durationMinutes, String title) {}
}
