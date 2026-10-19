package com.amateur.howmuchbackend.application.recap.policy;

import com.amateur.howmuchbackend.application.recap.model.RecapCategory;
import com.amateur.howmuchbackend.application.recap.model.RecapCategoryTotal;
import com.amateur.howmuchbackend.application.recap.model.RecapRecord;
import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
public class RecapCardPolicy {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public Cards summarize(List<RecapSourceFact> facts, Map<UUID, RecapCategory> categories) {
        Map<UUID, RecapSourceFact> settlements = new HashMap<>();
        for (RecapSourceFact fact : facts) {
            long amount = fact.confirmedOwnAmount();
            RecapSourceFact previous = settlements.putIfAbsent(fact.settlementId(), fact);
            if (previous != null && (previous.confirmedOwnAmount() != amount
                    || !Objects.equals(previous.completedAt(), fact.completedAt())
                    || !Objects.equals(previous.requestedAt(), fact.requestedAt())
                    || !Objects.equals(previous.title(), fact.title()))) {
                throw new IllegalStateException("Inconsistent recap settlement facts");
            }
        }
        if (settlements.isEmpty()) return new Cards(null, null);

        Map<String, CategoryStats> categoryStats = new HashMap<>();
        Map<DayOfWeek, Integer> weekdays = new EnumMap<>(DayOfWeek.class);
        for (RecapSourceFact fact : settlements.values()) {
            DayOfWeek weekday = fact.completedAt().atOffset(ZoneOffset.UTC).atZoneSameInstant(KST).getDayOfWeek();
            weekdays.merge(weekday, 1, Math::addExact);
            RecapCategory category = classified(categories.get(fact.settlementId()));
            if (category != null) {
                categoryStats.compute(category.code(), (code, previous) -> previous == null
                        ? new CategoryStats(category, fact.confirmedOwnAmount(), 1)
                        : new CategoryStats(category, Math.addExact(previous.amount(), fact.confirmedOwnAmount()),
                                Math.incrementExact(previous.count())));
            }
        }

        List<RecapCategoryTotal> ranked = new ArrayList<>();
        categoryStats.values().stream()
                .sorted(Comparator.comparingLong(CategoryStats::amount).reversed()
                        .thenComparing(value -> value.category().code()))
                .limit(3)
                .forEach(value -> ranked.add(new RecapCategoryTotal(ranked.size() + 1, value.category(), value.amount())));
        var busiest = weekdays.entrySet().stream()
                .sorted(Map.Entry.<DayOfWeek, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .findFirst().orElseThrow();
        var frequent = categoryStats.values().stream()
                .sorted(Comparator.comparingInt(CategoryStats::count).reversed()
                        .thenComparing(value -> value.category().code()))
                .map(value -> new RecapRecord.FrequentCategory(value.category(), value.count()))
                .findFirst().orElse(null);
        var largest = settlements.values().stream()
                .min(Comparator.comparingLong(RecapSourceFact::confirmedOwnAmount).reversed()
                        .thenComparing(RecapSourceFact::settlementId)).orElseThrow();
        var fastest = settlements.values().stream()
                .filter(fact -> fact.requestedAt() != null && !fact.requestedAt().isAfter(fact.completedAt())
                        && fact.title() != null && !fact.title().isBlank())
                // Compare the actual duration before truncating to minutes.
                .min(Comparator.comparing((RecapSourceFact fact) -> Duration.between(fact.requestedAt(), fact.completedAt()))
                        .thenComparing(RecapSourceFact::settlementId))
                .map(fact -> new RecapRecord.FastestSettlement(
                        Duration.between(fact.requestedAt(), fact.completedAt()).toMinutes(), fact.title()))
                .orElse(null);
        return new Cards(ranked.isEmpty() ? null : List.copyOf(ranked), new RecapRecord(
                new RecapRecord.BusiestWeekday(busiest.getKey(), busiest.getValue()), frequent,
                new RecapRecord.LargestSettlement(largest.confirmedOwnAmount(), classified(categories.get(largest.settlementId()))),
                fastest));
    }

    private RecapCategory classified(RecapCategory category) {
        return category == null || SettlementCategory.UNCATEGORIZED.equals(category.code()) ? null : category;
    }

    private record CategoryStats(RecapCategory category, long amount, int count) {}
    public record Cards(List<RecapCategoryTotal> categories, RecapRecord record) {}
}
