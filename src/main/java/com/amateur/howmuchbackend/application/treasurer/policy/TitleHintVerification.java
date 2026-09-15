package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.PersonalSettlement;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 질문에서 뽑은 정산 이름을 실제 정산과 대조해, 이름으로 좁혀도 되는지 판정한다.
 *
 * <p>정산 하나의 불변식이 아니라 사용자의 정산 전체를 함께 봐야 나오는 결정이라 Policy에 둔다.
 * 서비스는 이 결정을 받아 흐름만 조율한다.
 *
 * <p>좁히면 안 되는데 좁히면 금액을 틀리게 단정한다. 그래서 두 가지를 본다. 이름이 실제
 * 정산에 맞는가, 그리고 그 이름이 특정 정산을 가리키는 말인가.
 */
@Component
public class TitleHintVerification {
    /** 이름 근거로 보기에 너무 짧은 조각을 거른다. */
    private static final int MIN_TITLE_EVIDENCE = 2;

    /**
     * 그 자체로는 어떤 정산도 지목하지 못하는 말.
     *
     * <p>{@code "모임 정산 얼마 보내야 해?"} 는 모임 이름을 말한 것이 아닌데 힌트가 "모임정산"이
     * 되고, 뗀 형태 "모임"이 이름에 "모임"이 든 정산 하나에만 맞는다.
     */
    private static final List<String> GENERIC_TITLE_WORDS = List.of(
            "모임", "정산", "회비", "금액", "비용"
    );

    /**
     * 이름으로 좁혀도 되는 형태를 돌려준다. 좁히면 안 되면 {@code null}이다.
     *
     * @param all       사용자의 정산 전체. 기간을 적용하기 전이어야 한다.
     *                  이름은 맞고 기간만 다른 질문은 기간으로 걸러 0건이 나오는 것이 맞다.
     * @param titleHint 질문에서 뽑은 이름. 없으면 {@code null}
     */
    public String verify(List<PersonalSettlement> all, String titleHint) {
        if (titleHint == null) {
            return null;
        }
        if (matchesAnyTitle(all, titleHint)) {
            return titleHint;
        }
        // 힌트는 "모임"·"정산" 접미사를 포함한다. 정산 이름이 그 말을 빼고 "여행"이면
        // 제목이 힌트를 포함하지 못해 맞는 정산을 놓친다. 뗀 형태로 한 번 더 본다.
        String withoutSuffix = titleHint.replaceFirst("(모임|정산)$", "");
        if (isDiscriminating(withoutSuffix) && matchesAnyTitle(all, withoutSuffix)) {
            return withoutSuffix;
        }
        return null;
    }

    public boolean matchesTitle(PersonalSettlement settlement, String titleHint) {
        if (titleHint == null) {
            return true;
        }
        String title = settlement.title() == null ? "" : settlement.title().replaceAll("\\s+", "");
        return title.toLowerCase(Locale.ROOT).contains(titleHint.toLowerCase(Locale.ROOT));
    }

    /**
     * 뗀 형태가 이름 구실을 하는지 본다. 한 글자이거나 흔한 말이면 이름의 근거로 보지 않는다.
     */
    private boolean isDiscriminating(String candidate) {
        return candidate != null
                && candidate.length() >= MIN_TITLE_EVIDENCE
                && GENERIC_TITLE_WORDS.stream().noneMatch(candidate::equals);
    }

    private boolean matchesAnyTitle(List<PersonalSettlement> all, String titleHint) {
        return all.stream().anyMatch(settlement -> matchesTitle(settlement, titleHint));
    }
}
