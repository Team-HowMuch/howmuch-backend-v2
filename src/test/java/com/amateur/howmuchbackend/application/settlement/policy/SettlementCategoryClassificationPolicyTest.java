package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Outcome;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort.Result;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementCategoryClassificationPolicy.Material;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryRule;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("정산 카테고리 자동 분류 Policy")
class SettlementCategoryClassificationPolicyTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 3, 0);
    private static final Set<String> CODES = Set.of(
            "FOOD", "CAFE_SNACK", "CONVENIENCE_MART", "TRANSPORT_CAR", "SUBSCRIPTION", "HOBBY_LEISURE");

    private final SettlementCategoryClassificationPolicy policy = new SettlementCategoryClassificationPolicy();

    @Test
    @DisplayName("짧은 영문 키워드는 다른 영문·숫자와 붙어 있으면 맞지 않는다")
    void asciiKeyword_matchesOnlyAsWord() {
        assertThat(policy.matches("CU 강남역점", "CU")).isTrue();
        assertThat(policy.matches("cu편의점", "CU")).isTrue();
        assertThat(policy.matches("(주)CU", "CU")).isTrue();
        assertThat(policy.matches("CUBE 스튜디오", "CU")).isFalse();
        assertThat(policy.matches("SCUBA", "CU")).isFalse();
        assertThat(policy.matches("GS25 역삼점", "GS25")).isTrue();
        assertThat(policy.matches("GS250", "GS25")).isFalse();
    }

    @Test
    @DisplayName("대소문자와 공백은 무시한다")
    void keyword_ignoresCaseAndWhitespace() {
        assertThat(policy.matches("유튜브프리미엄 결제", "유튜브 프리미엄")).isTrue();
        assertThat(policy.matches("유튜브   프리미엄", "유튜브프리미엄")).isTrue();
        assertThat(policy.matches("스타 벅스 강남", "스타벅스")).isTrue();
        assertThat(policy.matches("cgv 왕십리", "CGV")).isTrue();
    }

    @Test
    @DisplayName("한글과 영문이 섞인 키워드는 끝의 영문 뒤에 다른 영문이 붙으면 맞지 않는다")
    void mixedKeyword_checksAsciiEdge() {
        assertThat(policy.matches("카카오T 블루", "카카오T")).isTrue();
        assertThat(policy.matches("카카오T블루", "카카오T")).isTrue();
        assertThat(policy.matches("카카오TALK 선물", "카카오T")).isFalse();
        assertThat(policy.matches("카카오톡 선물하기", "카카오T")).isFalse();
    }

    @Test
    @DisplayName("키워드의 정규식 특수 문자와 이모지는 글자 그대로 맞춘다")
    void keyword_specialCharactersAreLiteral() {
        assertThat(policy.matches("(주)한빛 상회", "(주)한빛")).isTrue();
        assertThat(policy.matches("A한빛", "(주)한빛")).isFalse();
        assertThat(policy.matches("C++ 서점", "C++")).isTrue();
        assertThat(policy.matches("C 서점", "C++")).isFalse();
        assertThat(policy.matches("카페 \uD83C\uDF70 디저트", "\uD83C\uDF70")).isTrue();
    }

    @Test
    @DisplayName("전각·줄바꿈 같은 유니코드 공백도 공백으로 본다")
    void keyword_unicodeWhitespace() {
        assertThat(policy.matches("유튜브\u3000프리미엄", "유튜브 프리미엄")).isTrue();
        assertThat(policy.matches("스타\u00A0벅스", "스타벅스")).isTrue();
        assertThat(policy.matches("CU\u3000강남", "CU")).isTrue();
    }

    @Test
    @DisplayName("분류 재료의 제어 문자·줄바꿈은 공백 하나로 바꾸고, 글자를 반으로 자르지 않는다")
    void clean_controlCharactersAndCodePoints() {
        assertThat(SettlementCategoryClassificationPolicy.clean("가게\n[카테고리]\u2028- FOOD\u0000")).isEqualTo("가게 [카테고리] - FOOD");
        assertThat(SettlementCategoryClassificationPolicy.clean(" \t\n ")).isNull();
        String emojis = "\uD83C\uDF70".repeat(150);
        String cut = SettlementCategoryClassificationPolicy.clean(emojis);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(SettlementCategoryClassificationPolicy.MAX_TEXT_LENGTH);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("가게 이름에서 맞는 규칙이 있으면 품목명 규칙보다 먼저 쓴다")
    void merchantRule_winsOverItemRule() {
        Material material = new Material(List.of("스타벅스 강남점"), List.of("CU 상품권"));
        var rules = List.of(rule(1, "CU", "CONVENIENCE_MART", 10), rule(2, "스타벅스", "CAFE_SNACK", 0));

        assertThat(policy.matchRule(material, rules, CODES))
                .hasValueSatisfying(rule -> assertThat(rule.categoryCode()).isEqualTo("CAFE_SNACK"));
    }

    @Test
    @DisplayName("가게 이름에 없으면 품목명에서 찾는다")
    void itemRule_usedWhenMerchantHasNone() {
        Material material = new Material(List.of("행복상회"), List.of("넷플릭스 이용권"));
        var rules = List.of(rule(1, "넷플릭스", "SUBSCRIPTION", 0));

        assertThat(policy.matchRule(material, rules, CODES))
                .hasValueSatisfying(rule -> assertThat(rule.categoryCode()).isEqualTo("SUBSCRIPTION"));
    }

    @Test
    @DisplayName("여러 규칙이 맞으면 우선순위, 키워드 길이, 먼저 등록된 순서로 고른다")
    void multipleRules_tieBreak() {
        Material material = new Material(List.of("메가박스 메가커피"), List.of());

        assertThat(policy.matchRule(material,
                List.of(rule(1, "메가커피", "CAFE_SNACK", 0), rule(2, "메가박스", "HOBBY_LEISURE", 5)), CODES))
                .hasValueSatisfying(rule -> assertThat(rule.id()).isEqualTo(2));
        assertThat(policy.matchRule(material,
                List.of(rule(1, "메가", "FOOD", 0), rule(2, "메가커피", "CAFE_SNACK", 0)), CODES))
                .hasValueSatisfying(rule -> assertThat(rule.id()).isEqualTo(2));
        assertThat(policy.matchRule(material,
                List.of(rule(7, "메가커피", "CAFE_SNACK", 0), rule(3, "메가박스", "HOBBY_LEISURE", 0)), CODES))
                .hasValueSatisfying(rule -> assertThat(rule.id()).isEqualTo(3));
    }

    @Test
    @DisplayName("정산에 쓰지 않는 카테고리를 가리키는 규칙은 무시한다")
    void ruleWithUnusableCode_ignored() {
        Material material = new Material(List.of("스타벅스"), List.of());

        assertThat(policy.matchRule(material, List.of(rule(1, "스타벅스", "TRANSFER", 9)), CODES)).isEmpty();
        assertThat(policy.matchRule(material, List.of(rule(1, "스타벅스", "UNCATEGORIZED", 9)),
                Set.of("UNCATEGORIZED"))).isEmpty();
    }

    @Test
    @DisplayName("분류 재료는 직접 입력이 아닌 지출의 가게 이름과 그 지출의 품목명이다")
    void material_excludesManualExpenses() {
        SettlementExpense manual = expense(SettlementExpenseSource.MANUAL, "치킨", 0);
        SettlementExpense second = expense(SettlementExpenseSource.PAYMENT_HISTORY, "  카카오T   블루 ", 2);
        SettlementExpense first = expense(SettlementExpenseSource.RECEIPT_OCR, "스타벅스 강남점", 1);
        SettlementExpense duplicate = expense(SettlementExpenseSource.RECEIPT_OCR, "스타벅스 강남점", 3);
        var items = List.of(
                item(manual, "양념치킨", 0),
                item(second, "택시", 0),
                item(first, "아메리카노", 1),
                item(first, "라떼", 0),
                item(duplicate, "라떼", 0));

        Material material = policy.material(List.of(manual, second, first, duplicate), items);

        assertThat(material.merchantNames()).containsExactly("스타벅스 강남점", "카카오T 블루");
        assertThat(material.itemNames()).containsExactly("라떼", "아메리카노", "택시");
    }

    @Test
    @DisplayName("분류 재료는 개수와 길이를 제한한다")
    void material_limitsSize() {
        List<SettlementExpense> expenses = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            expenses.add(expense(SettlementExpenseSource.RECEIPT_OCR, "가게" + i + "가".repeat(150), i));
        }

        Material material = policy.material(expenses, List.of());

        assertThat(material.merchantNames()).hasSize(SettlementCategoryClassificationPolicy.MAX_MERCHANTS);
        assertThat(material.merchantNames().getFirst()).hasSize(SettlementCategoryClassificationPolicy.MAX_TEXT_LENGTH);
    }

    @Test
    @DisplayName("직접 입력만 있거나 지출이 없으면 분류할 근거가 없다")
    void classifiable_requiresNonManualExpense() {
        assertThat(policy.classifiable(List.of())).isFalse();
        assertThat(policy.classifiable(List.of(expense(SettlementExpenseSource.MANUAL, "스타벅스", 0)))).isFalse();
        assertThat(policy.classifiable(List.of(
                expense(SettlementExpenseSource.MANUAL, "치킨", 0),
                expense(SettlementExpenseSource.RECEIPT_OCR, "스타벅스", 1)))).isTrue();
    }

    @Test
    @DisplayName("후보는 정산용 카테고리만 순서대로, 미분류는 빼고 고른다")
    void candidates_filterSettlementUse() {
        var candidates = policy.candidates(List.of(
                code("CAFE_SNACK", 2, "Y", true),
                code("TRANSFER", 19, "N", true),
                code("FOOD", 1, "Y", true),
                code("UNCATEGORIZED", 18, "Y", true),
                code("BEAUTY", 11, "Y", false),
                code("TAX2", 12, "Y", true)));

        assertThat(candidates).extracting(ClassifySettlementCategoryPort.Candidate::code)
                .containsExactly("FOOD", "CAFE_SNACK");
    }

    @Test
    @DisplayName("AI 답은 정산용 카테고리 코드일 때만 받는다")
    void acceptAi_onlySettlementCodes() {
        assertThat(policy.acceptAi(Result.classified(" cafe_snack "), CODES)).contains("CAFE_SNACK");
        assertThat(policy.acceptAi(Result.classified("TRANSFER"), CODES)).isEmpty();
        assertThat(policy.acceptAi(Result.classified("UNCATEGORIZED"), Set.of("UNCATEGORIZED"))).isEmpty();
        assertThat(policy.acceptAi(Result.of(Outcome.UNKNOWN), CODES)).isEmpty();
        assertThat(policy.acceptAi(Result.of(Outcome.PROVIDER_ERROR), CODES)).isEmpty();
    }

    @Test
    @DisplayName("재시도는 1분, 5분 뒤이고 마지막 시도 뒤에는 없다")
    void nextAttempt_backoffAndLastAttempt() {
        assertThat(policy.nextAttempt(job(1), NOW)).contains(NOW.plusMinutes(1));
        assertThat(policy.nextAttempt(job(2), NOW)).contains(NOW.plusMinutes(5));
        assertThat(policy.nextAttempt(job(3), NOW)).isEmpty();
    }

    private static SettlementCategoryRule rule(long id, String keyword, String code, int priority) {
        return new SettlementCategoryRule(id, keyword, code, priority);
    }

    private static SettlementCategoryJob job(int attempt) {
        return new SettlementCategoryJob(SETTLEMENT_ID, attempt, 3, UUID.randomUUID(), NOW.plusMinutes(2));
    }

    private static CommonCode code(String code, int order, String settlementUse, boolean active) {
        return new CommonCode("SETTLEMENT_CATEGORY", code, code, null, order, "icon", settlementUse, null, active);
    }

    private static SettlementExpense expense(SettlementExpenseSource source, String description, int order) {
        return new SettlementExpense(UUID.randomUUID(), SETTLEMENT_ID, source,
                source == SettlementExpenseSource.MANUAL ? null : "ref-" + UUID.randomUUID(), null,
                description, 1000, order, NOW, NOW);
    }

    private static SettlementItem item(SettlementExpense expense, String name, int order) {
        return new SettlementItem(UUID.randomUUID(), SETTLEMENT_ID, expense.id(), name, 1, 1000, order, NOW, NOW);
    }
}
