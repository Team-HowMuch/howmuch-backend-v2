package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("정산 이름 힌트 확인 정책")
class TitleHintVerificationTest {
    private final TitleHintVerification policy = new TitleHintVerification();

    private final List<PersonalSettlement> all = List.of(
            settlement("8월 모임 정산"),
            settlement("여행 정산"),
            settlement("점심 회비"));

    @ParameterizedTest
    @ValueSource(strings = {"모임정산", "정산", "모임", "회비", "금액비용"})
    @DisplayName("일반어만으로 이루어진 힌트는 실제 정산에 맞아도 쓰지 않는다")
    void titleHint_genericWordsOnly_rejected(String hint) {
        assertThat(policy.verify(all, hint)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "여행정산, 여행정산",
            "8월모임, 8월모임",
            "여행모임, 여행",
            "점심회비, 점심회비"
    })
    @DisplayName("일반어를 걷어내도 이름이 남으면 쓴다")
    void titleHint_nameRemainsAfterGenericRemoval_accepted(String hint, String expected) {
        assertThat(policy.verify(all, hint)).isEqualTo(expected);
    }

    @Test
    @DisplayName("맞는 정산이 없는 이름은 쓰지 않는다")
    void titleHint_withoutMatchingSettlement_rejected() {
        assertThat(policy.verify(all, "없는이름정산")).isNull();
    }

    @Test
    @DisplayName("이름이 없으면 좁히지 않는다")
    void titleHint_absent_noFiltering() {
        assertThat(policy.verify(all, null)).isNull();
    }

    @Test
    @DisplayName("한 글자 조각은 이름의 근거로 보지 않는다")
    void titleHint_singleCharacter_rejected() {
        assertThat(policy.verify(List.of(settlement("여행 정산")), "여")).isNull();
    }

    @Test
    @DisplayName("정산이 하나도 없으면 어떤 이름도 쓰지 않는다")
    void settlementList_empty_allHintsRejected() {
        assertThat(policy.verify(List.of(), "여행정산")).isNull();
    }

    @Test
    @DisplayName("이름 대조는 공백을 무시하고 대소문자를 가리지 않는다")
    void matchesIgnoringWhitespaceAndCase() {
        assertThat(policy.matchesTitle(settlement("MT 정산"), "mt정산")).isTrue();
        assertThat(policy.matchesTitle(settlement("여행 정산"), "여행정산")).isTrue();
        assertThat(policy.matchesTitle(settlement("여행 정산"), "회식")).isFalse();
    }

    @Test
    @DisplayName("이름이 없으면 모든 정산이 대조를 통과한다")
    void matchesEverySettlementWhenHintIsAbsent() {
        assertThat(policy.matchesTitle(settlement("여행 정산"), null)).isTrue();
    }

    private PersonalSettlement settlement(String title) {
        return new PersonalSettlement(
                UUID.randomUUID(),
                title,
                SettlementDirection.SEND,
                SettlementStatus.REQUESTED,
                LocalDateTime.of(2026, 8, 10, 0, 0),
                10_000L,
                "요청자"
        );
    }
}
