package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerGuideReply;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerKnowledge;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 사용법 안내 답변을 사용자에게 보여 줘도 되는지 판단한다.
 *
 * <p>LLM이 문서에 없는 기능을 지어내는 것을 문장만 보고 막을 수는 없다. 대신 근거 ID가 실제 문서에
 * 있는지, 문서에 없는 숫자(금액, 시간, 자릿수 등)를 말하지 않는지처럼 서버가 확인할 수 있는 것을 본다.
 * 하나라도 어긋나면 답변을 버리고 고정 안내로 되돌린다.
 */
@Component
public class TreasurerGuideCheck {
    static final int MAX_ANSWER_LENGTH = 600;
    static final int MAX_FOLLOW_UPS = 3;
    static final int MAX_FOLLOW_UP_LENGTH = 40;

    /**
     * 단위가 붙은 숫자. 안내 답변의 숫자는 모두 문서에 있는 값이어야 한다.
     * "30분"을 "5분"으로, "8자리"를 "6자리"로 바꿔 말하는 것도 틀린 안내다.
     */
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "₩\\s*\\d[\\d,]*|\\d[\\d,]*\\s*(?:만\\s*원|천\\s*원|원|시간|분|초|자리|자|명|개|번|회|건|일|시|단계|살|세)"
    );
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern FENCE = Pattern.compile("```[A-Za-z]*");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s*");

    public Outcome check(TreasurerGuideReply reply, TreasurerKnowledge knowledge) {
        if (reply == null || knowledge == null) {
            return Outcome.rejected("no reply");
        }
        if (reply.route() != TreasurerGuideReply.Route.GUIDE) {
            return Outcome.rejected("route " + reply.route());
        }
        String answer = clean(reply.answer());
        if (answer.isEmpty() || answer.length() > MAX_ANSWER_LENGTH) {
            return Outcome.rejected("answer length " + answer.length());
        }
        if (reply.sources().isEmpty()) {
            return Outcome.rejected("no source");
        }
        if (!reply.sources().stream().allMatch(knowledge::knows)) {
            return Outcome.rejected("unknown source");
        }
        if (mentionsUndocumentedNumber(answer, knowledge)) {
            return Outcome.rejected("undocumented number");
        }
        return Outcome.accepted(new Accepted(
                answer,
                List.copyOf(reply.sources()),
                followUps(reply.followUps(), knowledge)
        ));
    }

    /**
     * 숫자 표현이 문서에 그대로 있는지 본다. 공백은 무시하고, 앞이 숫자나 쉼표면 다른 숫자의 일부로 본다.
     * 그래서 "0원"은 문서의 "10,000원" 안에 있어도 인정하지 않는다.
     */
    private boolean mentionsUndocumentedNumber(String text, TreasurerKnowledge knowledge) {
        String documented = compact(knowledge.text());
        Matcher matcher = NUMBER_WITH_UNIT.matcher(text);
        while (matcher.find()) {
            if (!documentedAsWhole(documented, compact(matcher.group()))) {
                return true;
            }
        }
        return false;
    }

    private boolean documentedAsWhole(String documented, String token) {
        int from = documented.indexOf(token);
        while (from >= 0) {
            if (from == 0 || !isNumberPart(documented.charAt(from - 1))) {
                return true;
            }
            from = documented.indexOf(token, from + 1);
        }
        return false;
    }

    private boolean isNumberPart(char value) {
        return Character.isDigit(value) || value == ',';
    }

    /** 후속 질문은 누르면 그대로 다시 질문으로 들어오므로 짧고 평범한 문장만 남긴다. */
    private List<String> followUps(List<String> candidates, TreasurerKnowledge knowledge) {
        Set<String> kept = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String followUp = candidate == null ? "" : candidate.strip();
            if (followUp.isEmpty()
                    || followUp.length() > MAX_FOLLOW_UP_LENGTH
                    || followUp.contains("http")
                    || followUp.contains("\n")
                    || mentionsUndocumentedNumber(followUp, knowledge)) {
                continue;
            }
            kept.add(followUp);
            if (kept.size() == MAX_FOLLOW_UPS) {
                break;
            }
        }
        return new ArrayList<>(kept);
    }

    private String clean(String answer) {
        if (answer == null) {
            return "";
        }
        String cleaned = FENCE.matcher(answer).replaceAll("").replace("**", "");
        return HEADING.matcher(cleaned).replaceAll("").strip();
    }

    private static String compact(String value) {
        return WHITESPACE.matcher(value).replaceAll("");
    }

    public record Accepted(String answer, List<String> sources, List<String> followUps) {
    }

    /**
     * 검사 결과. 받아들이지 않았으면 {@code rejectedBecause}에 이유가 있다. 이유에는 질문이나 답변 원문을 넣지 않는다.
     */
    public record Outcome(Optional<Accepted> accepted, String rejectedBecause) {
        static Outcome accepted(Accepted accepted) {
            return new Outcome(Optional.of(accepted), null);
        }

        static Outcome rejected(String reason) {
            return new Outcome(Optional.empty(), reason);
        }
    }
}
