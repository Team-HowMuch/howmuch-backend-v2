package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryRule;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 정산 카테고리 자동 분류 규칙(STL-008-001, STL-008-004).
 *
 * <p>분류 재료는 직접 입력이 아닌 지출(영수증·결제 내역)의 가게 이름·거래명과 그 지출의 품목명이다.
 * 규칙 사전을 가게 이름·거래명에서 먼저 찾고, 없으면 품목명에서 찾는다.
 */
@Component
public class SettlementCategoryClassificationPolicy {
    public static final Duration LEASE = Duration.ofMinutes(2);
    static final int MAX_MERCHANTS = 5;
    static final int MAX_ITEMS = 30;
    static final int MAX_TEXT_LENGTH = 100;
    private static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration NEXT_RETRY_DELAY = Duration.ofMinutes(5);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}|\\p{Cf}");
    private static final Pattern CATEGORY_CODE = Pattern.compile("^[A-Z][A-Z_]{0,49}$");
    private static final Comparator<SettlementCategoryRule> RULE_ORDER = Comparator
            .comparingInt(SettlementCategoryRule::priority).reversed()
            .thenComparing(Comparator.comparingInt(
                    (SettlementCategoryRule rule) -> normalize(rule.keyword()).length()).reversed())
            .thenComparingLong(SettlementCategoryRule::id);

    /** 요청 시점에 분류할 근거가 있는지. 직접 입력만 있으면 바로 미분류로 저장한다. */
    public boolean classifiable(List<SettlementExpense> expenses) {
        return expenses.stream()
                .filter(expense -> expense.source() != SettlementExpenseSource.MANUAL)
                .anyMatch(expense -> clean(expense.description()) != null);
    }

    public Material material(List<SettlementExpense> expenses, List<SettlementItem> items) {
        List<SettlementExpense> sources = expenses.stream()
                .filter(expense -> expense.source() != SettlementExpenseSource.MANUAL)
                .sorted(Comparator.comparingInt(SettlementExpense::displayOrder))
                .toList();
        Map<UUID, Integer> expenseOrder = new HashMap<>();
        for (int index = 0; index < sources.size(); index++) {
            expenseOrder.put(sources.get(index).id(), index);
        }
        List<String> merchants = sources.stream()
                .map(SettlementExpense::description)
                .map(SettlementCategoryClassificationPolicy::clean)
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_MERCHANTS)
                .toList();
        List<String> itemNames = items.stream()
                .filter(item -> expenseOrder.containsKey(item.expenseId()))
                .sorted(Comparator.comparingInt((SettlementItem item) -> expenseOrder.get(item.expenseId()))
                        .thenComparingInt(SettlementItem::displayOrder))
                .map(SettlementItem::name)
                .map(SettlementCategoryClassificationPolicy::clean)
                .filter(Objects::nonNull)
                .distinct()
                .limit(MAX_ITEMS)
                .toList();
        return new Material(merchants, itemNames);
    }

    /** 정산에 쓰는 카테고리(정산 사용 Y, 미분류 제외)를 순서대로. */
    public List<ClassifySettlementCategoryPort.Candidate> candidates(List<CommonCode> codes) {
        return codes.stream()
                .filter(CommonCode::active)
                .filter(code -> SettlementCategory.GROUP_CODE.equals(code.groupCode()))
                .filter(code -> SettlementCategory.SETTLEMENT_USE.equals(code.attr2()))
                .filter(code -> !SettlementCategory.UNCATEGORIZED.equals(code.code()))
                .filter(code -> CATEGORY_CODE.matcher(code.code()).matches())
                .sorted(Comparator.comparingInt(CommonCode::sortOrder))
                .map(code -> new ClassifySettlementCategoryPort.Candidate(code.code(), code.name(), code.description()))
                .toList();
    }

    /**
     * 규칙 사전에서 고른다. 가게 이름·거래명에서 맞는 규칙이 있으면 그중에서, 없으면 품목명에서 고른다.
     * 여러 개가 맞으면 우선순위가 높은 것, 같으면 키워드가 긴 것, 그다음 먼저 등록된 것.
     */
    public Optional<SettlementCategoryRule> matchRule(Material material, List<SettlementCategoryRule> rules,
            Set<String> categoryCodes) {
        List<SettlementCategoryRule> usable = rules.stream()
                .filter(rule -> categoryCodes.contains(rule.categoryCode()))
                .filter(rule -> !SettlementCategory.UNCATEGORIZED.equals(rule.categoryCode()))
                .filter(rule -> !normalize(rule.keyword()).isEmpty())
                .toList();
        return best(material.merchantNames(), usable).or(() -> best(material.itemNames(), usable));
    }

    /** AI가 고른 코드를 정산용 카테고리일 때만 받는다. 그 밖에는 미분류. */
    public Optional<String> acceptAi(ClassifySettlementCategoryPort.Result result, Set<String> categoryCodes) {
        if (result == null || result.outcome() != ClassifySettlementCategoryPort.Outcome.CLASSIFIED
                || result.categoryCode() == null) {
            return Optional.empty();
        }
        String code = result.categoryCode().strip().toUpperCase(Locale.ROOT);
        if (SettlementCategory.UNCATEGORIZED.equals(code) || !categoryCodes.contains(code)) {
            return Optional.empty();
        }
        return Optional.of(code);
    }

    /** 다음 시도 시각. 마지막 시도였으면 비어 있다. */
    public Optional<LocalDateTime> nextAttempt(SettlementCategoryJob job, LocalDateTime now) {
        if (job.lastAttempt()) {
            return Optional.empty();
        }
        return Optional.of(now.plus(job.attemptCount() <= 1 ? FIRST_RETRY_DELAY : NEXT_RETRY_DELAY));
    }

    public static Set<String> codes(List<ClassifySettlementCategoryPort.Candidate> candidates) {
        return candidates.stream().map(ClassifySettlementCategoryPort.Candidate::code).collect(Collectors.toSet());
    }

    boolean matches(String text, String keyword) {
        if (text == null) {
            return false;
        }
        String normalized = normalize(keyword);
        if (normalized.isEmpty()) {
            return false;
        }
        return pattern(normalized).matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    private Optional<SettlementCategoryRule> best(List<String> texts, List<SettlementCategoryRule> rules) {
        return rules.stream()
                .filter(rule -> texts.stream().anyMatch(text -> matches(text, rule.keyword())))
                .min(RULE_ORDER);
    }

    /**
     * 공백은 무시하고, 영문·숫자로 시작하거나 끝나는 키워드는 그 앞뒤에 다른 영문·숫자가 붙어 있으면 맞지 않는다.
     * 예: "CU"는 "CU 강남점", "CU편의점"에 맞고 "CUBE"에는 맞지 않는다.
     */
    private static Pattern pattern(String normalizedKeyword) {
        int[] codePoints = normalizedKeyword.codePoints().toArray();
        StringBuilder regex = new StringBuilder();
        if (asciiAlphanumeric(codePoints[0])) {
            regex.append("(?<![a-z0-9])");
        }
        for (int index = 0; index < codePoints.length; index++) {
            if (index > 0) {
                regex.append("\\s*");
            }
            regex.append(Pattern.quote(new String(Character.toChars(codePoints[index]))));
        }
        if (asciiAlphanumeric(codePoints[codePoints.length - 1])) {
            regex.append("(?![a-z0-9])");
        }
        return Pattern.compile(regex.toString(), Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static boolean asciiAlphanumeric(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= '0' && codePoint <= '9');
    }

    static String normalize(String value) {
        return value == null ? "" : WHITESPACE.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 줄바꿈·제어 문자를 공백으로 바꾸고 공백을 하나로 합친 뒤, 글자(코드 포인트) 기준 100자까지 자른다. */
    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = WHITESPACE.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll(" ").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) <= MAX_TEXT_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_TEXT_LENGTH)).strip();
    }

    public record Material(List<String> merchantNames, List<String> itemNames) {
        public Material {
            merchantNames = List.copyOf(merchantNames);
            itemNames = List.copyOf(itemNames);
        }

        public boolean empty() {
            return merchantNames.isEmpty() && itemNames.isEmpty();
        }
    }
}
