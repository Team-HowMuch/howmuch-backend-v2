package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.RecapRecord;

import java.time.DayOfWeek;

public record RecapRecordResult(BusiestWeekday busiestWeekday, FrequentCategory frequentCategory,
                                LargestSettlement largestSettlement, FastestSettlement fastestSettlement) {
    public record BusiestWeekday(DayOfWeek weekday, int count) {}
    public record FrequentCategory(RecapCategoryResult category, int count) {}
    public record LargestSettlement(long totalOwnAmount, RecapCategoryResult category) {}
    public record FastestSettlement(long durationMinutes, String title) {}

    public static RecapRecordResult from(RecapRecord record) {
        if (record == null) return null;
        var busiest = record.busiestWeekday();
        var frequent = record.frequentCategory();
        var largest = record.largestSettlement();
        var fastest = record.fastestSettlement();
        return new RecapRecordResult(
                busiest == null ? null : new BusiestWeekday(busiest.weekday(), busiest.count()),
                frequent == null ? null : new FrequentCategory(RecapCategoryResult.from(frequent.category()), frequent.count()),
                largest == null ? null : new LargestSettlement(largest.totalOwnAmount(), RecapCategoryResult.from(largest.category())),
                fastest == null ? null : new FastestSettlement(fastest.durationMinutes(), fastest.title()));
    }
}
