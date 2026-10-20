package com.amateur.howmuchbackend.application.recap.policy;

import com.amateur.howmuchbackend.application.recap.model.RecapCategory;
import com.amateur.howmuchbackend.application.recap.model.RecapSourceFact;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RecapCardPolicyTest {
    private final RecapCardPolicy policy = new RecapCardPolicy();
    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 9, 6, 15, 0);
    private static final RecapCategory FOOD = new RecapCategory("FOOD", "식비", "food");
    private static final RecapCategory CAFE = new RecapCategory("CAFE_SNACK", "카페 / 간식", "cafe-snack");

    @Test
    void categories_duplicateCompanions_rankThreeDistinctSettlementTotals() {
        var facts = List.of(fact(1, 100), fact(1, 100), fact(2, 200), fact(3, 400), fact(4, 300), fact(5, 10));
        var shopping = new RecapCategory("SHOPPING", "쇼핑", "shopping");
        var living = new RecapCategory("LIVING", "생활", "living");
        var cards = policy.summarize(facts, Map.of(id(1), FOOD, id(2), FOOD, id(3), CAFE, id(4), shopping, id(5), living));
        assertThat(cards.categories()).extracting(v -> v.category().code()).containsExactly("CAFE_SNACK", "FOOD", "SHOPPING");
        assertThat(cards.categories()).extracting(v -> v.rank()).containsExactly(1, 2, 3);
        assertThat(cards.categories()).extracting(v -> v.totalOwnAmount()).containsExactly(400L, 300L, 300L);
        assertThat(cards.record().frequentCategory().category()).isEqualTo(FOOD);
        assertThat(cards.record().frequentCategory().count()).isEqualTo(2);
        assertThat(cards.record().busiestWeekday().count()).isEqualTo(5);
    }

    @Test
    void cards_tiesAndReorderedFacts_keepDeterministicResults() {
        var facts = new ArrayList<>(List.of(fact(2, 100), fact(1, 100)));
        var categories = Map.of(id(1), FOOD, id(2), CAFE);
        var expected = policy.summarize(facts, categories);
        Collections.reverse(facts);
        assertThat(policy.summarize(facts, categories)).isEqualTo(expected);
        assertThat(expected.categories()).extracting(v -> v.category().code()).containsExactly("CAFE_SNACK", "FOOD");
        assertThat(expected.record().frequentCategory().category()).isEqualTo(CAFE);
        assertThat(expected.record().largestSettlement().category()).isEqualTo(FOOD);
    }

    @Test
    void categories_unclassifiedFacts_hideCategoryCardButKeepAvailableRecords() {
        var cards = policy.summarize(List.of(fact(1, 200), fact(2, 100)),
                Map.of(id(1), new RecapCategory("UNCATEGORIZED", "미분류", "uncategorized")));
        assertThat(cards.categories()).isNull();
        assertThat(cards.record().frequentCategory()).isNull();
        assertThat(cards.record().largestSettlement().totalOwnAmount()).isEqualTo(200);
        assertThat(cards.record().largestSettlement().category()).isNull();
        assertThat(cards.record().fastestSettlement()).isNull();
    }

    @Test
    void cards_confirmedZeroSubmission_includeZeroAmountAndRequestMetadata() {
        var source = fact(1, null, MONDAY, MONDAY.minusSeconds(30), "0원 정산").withConfirmedZero(true);
        var cards = policy.summarize(List.of(source), Map.of(id(1), FOOD));
        assertThat(cards.categories().getFirst().totalOwnAmount()).isZero();
        assertThat(cards.record().largestSettlement().totalOwnAmount()).isZero();
        assertThat(cards.record().fastestSettlement().durationMinutes()).isZero();
        assertThat(cards.record().fastestSettlement().title()).isEqualTo("0원 정산");
    }

    @Test
    void busiestWeekday_kstMidnightAndTies_chooseMondayBeforeSunday() {
        var cards = policy.summarize(List.of(
                fact(1, 100L, MONDAY, null, "월요일"),
                fact(2, 100L, MONDAY.minusSeconds(1), null, "일요일")), Map.of());
        assertThat(cards.record().busiestWeekday().weekday()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(cards.record().busiestWeekday().count()).isOne();
    }

    @Test
    void fastestSettlement_sameRoundedMinute_compareActualDurationFirst() {
        var cards = policy.summarize(List.of(
                fact(1, 100L, MONDAY, MONDAY.minusSeconds(110), "느린 정산"),
                fact(3, 100L, MONDAY, MONDAY.minusSeconds(61), "동률 뒷순서"),
                fact(2, 100L, MONDAY, MONDAY.minusSeconds(61), "빠른 정산"),
                fact(4, 100L, MONDAY, MONDAY.plusSeconds(1), "잘못된 시각"),
                fact(5, 100L, MONDAY, null, "요청 없음")), Map.of());
        assertThat(cards.record().fastestSettlement().durationMinutes()).isOne();
        assertThat(cards.record().fastestSettlement().title()).isEqualTo("빠른 정산");
    }

    @Test
    void fastestSettlement_noValidRequest_returnsNull() {
        var cards = policy.summarize(List.of(fact(1, 100),
                fact(2, 100L, MONDAY, MONDAY.plusMinutes(1), "미래 요청")), Map.of());
        assertThat(cards.record().fastestSettlement()).isNull();
    }

    @Test
    void cards_noSettlements_returnNullCards() {
        var cards = policy.summarize(List.of(), Map.of());
        assertThat(cards.categories()).isNull();
        assertThat(cards.record()).isNull();
    }

    @Test
    void categories_amountOverflow_rejectInsteadOfWrapping() {
        assertThatThrownBy(() -> policy.summarize(List.of(fact(1, Long.MAX_VALUE), fact(2, 1)),
                Map.of(id(1), FOOD, id(2), FOOD))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void cards_missingNegativeOrInconsistentShares_rejectInvalidSnapshot() {
        assertThatThrownBy(() -> policy.summarize(List.of(fact(1, null, MONDAY, null, "누락")), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.summarize(List.of(fact(1, -1)), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy.summarize(List.of(fact(1, 100), fact(1, 200)), Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    private RecapSourceFact fact(long id, long amount) { return fact(id, amount, MONDAY, null, "정산 " + id); }
    private RecapSourceFact fact(long id, Long amount, LocalDateTime completed, LocalDateTime requested, String title) {
        return new RecapSourceFact(id(id), completed, amount, false, UUID.randomUUID(), 1L,
                "참여자", "회원", "user", null, true, null, requested, title);
    }
    private UUID id(long value) { return new UUID(0, value); }
}
