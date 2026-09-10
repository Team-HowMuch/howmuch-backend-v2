package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InquiryParser {
    private static final List<String> PERIOD_THIS_WEEK = List.of("이번주", "이번 주");
    private static final List<String> PERIOD_THIS_MONTH = List.of("이번달", "이번 달");
    private static final List<String> PERIOD_LAST_MONTH = List.of("지난달", "지난 달", "저번달", "저번 달");
    /** 기간으로 매핑하지는 않지만 제목 추출에서는 질의 문법으로 취급하는 시간 표현 */
    private static final List<String> TIME_WORDS = List.of(
            "지난주", "지난 주", "저번주", "저번 주", "오늘", "어제", "내일", "요즘", "최근", "그동안"
    );

    /**
     * 정산 맥락을 가리키는 단어.
     * 약한 표현은 이 중 하나가 질문에 함께 있을 때만 의도로 인정한다.
     */
    private static final List<String> SETTLEMENT_CONTEXT = List.of(
            "정산", "모임", "돈", "금액", "회비", "입금", "송금", "이체", "계좌"
    );
    /** 지출 질문은 금액을 묻는 표현도 맥락으로 본다 */
    private static final List<String> EXPENSE_CONTEXT = merge(SETTLEMENT_CONTEXT, List.of("얼마"));

    /** 내가 낼 돈. 단독으로도 뜻이 분명하다 */
    private static final List<String> SEND_STRONG = List.of(
            "보낼", "낼정산", "보낼돈", "낼돈", "내야", "보내야", "송금", "안보낸", "안냈", "안낸"
    );
    /** 부담·지불 표현. 정산 맥락이 있어야 인정한다 */
    private static final List<String> SEND_WEAK = List.of("줘야", "부담해야", "부담할", "지불해야");

    /** 내가 받을 돈. 단독으로도 뜻이 분명하다 */
    private static final List<String> RECEIVE_STRONG = List.of("받을", "받을돈", "받을금액", "받아야");
    /** 입금 예정 표현. "언제 들어와?" 처럼 정산과 무관하게도 쓰여 맥락이 필요하다 */
    private static final List<String> RECEIVE_WEAK = List.of("들어올", "들어와");

    /** 남이 나에게 아직 내지 않았다는 표현. 보낼 정산과 방향이 반대라 먼저 본다 */
    private static final List<String> UNPAID_TO_ME = List.of(
            "송금안", "아직송금", "안낸사람", "안보낸사람", "안낸멤버", "안보낸멤버"
    );

    /** 지출 자체를 가리키는 표현 */
    private static final List<String> EXPENSE_STRONG = List.of("지출", "소비", "결제", "사용했", "사용내역");
    /** 썼다는 표현. 금액이나 정산 맥락이 있어야 인정한다 */
    private static final List<String> EXPENSE_WEAK = List.of("썼", "지불했", "얼마나했");

    /** 진행 상태를 직접 묻는 표현 */
    private static final List<String> STATUS_STRONG = List.of(
            "진행상태", "진행 상황", "진행상황", "진행중인", "진행 중인", "진행중", "진행 중",
            "정산상태", "정산 있어", "정산있어", "어디까지"
    );
    /** 남았다·안 끝났다는 표현. "밥 남았어?" 처럼 일상어라 맥락이 필요하다 */
    private static final List<String> STATUS_WEAK = List.of(
            "안끝났", "안끝난", "남았", "남은", "처리해야", "처리할", "처리하지"
    );

    private static final List<String> DUE_KEYWORDS = List.of("마감해야 할 정산", "마감할 정산", "마감해야", "마감할", "마감");
    private static final List<String> GUIDE_KEYWORDS = List.of("ocr", "영수증");
    private static final List<String> FILLER_KEYWORDS = List.of("알려줘", "어떻게해", "어떻게", "누구야", "있어");

    private static final Pattern TITLE_SUFFIX = Pattern.compile("모임|정산");
    private static final List<String> TITLE_STOP_WORDS = List.of(
            "왜", "언제", "어디", "어떤", "무슨", "뭐", "누가", "누구", "얼마", "아직", "지금", "방금"
    );
    /**
     * 제목 바로 앞 어절이 이 중 하나면 제목이 아니라 조사·접속 조각이다.
     * 어절 단위로 정확히 비교한다. 부분 문자열로 보면 "이전모임" 같은 제목까지 걸린다.
     */
    private static final List<String> TITLE_PARTICLE_TOKENS = List.of(
            "중에", "중에서", "그중", "안에", "에는", "에서", "말고", "건데",
            "부터", "까지", "동안", "이후", "이전", "대신"
    );
    private static final int MIN_TITLE_PREFIX = 1;
    private static final int MAX_TITLE_PREFIX = 20;

    @SafeVarargs
    private static List<String> merge(List<String>... groups) {
        List<String> merged = new ArrayList<>();
        for (List<String> group : groups) {
            merged.addAll(group);
        }
        return List.copyOf(merged);
    }

    public ParsedInquiry parse(String question) {
        if (question == null || question.isBlank()) {
            return new ParsedInquiry(InquiryIntent.UNKNOWN, null, null);
        }
        String spaceless = question.replaceAll("\\s+", "");
        String compact = spaceless.toLowerCase(Locale.ROOT);
        InquiryPeriod period = periodOf(compact);
        InquiryIntent intent = intentOf(compact);
        String titleHint = usesTitleFilter(intent) ? titleHint(question, spaceless, compact) : null;
        return new ParsedInquiry(intent, period, titleHint);
    }

    private InquiryPeriod periodOf(String compact) {
        if (containsAny(compact, PERIOD_THIS_WEEK)) {
            return InquiryPeriod.THIS_WEEK;
        }
        if (containsAny(compact, PERIOD_LAST_MONTH)) {
            return InquiryPeriod.LAST_MONTH;
        }
        if (containsAny(compact, PERIOD_THIS_MONTH)) {
            return InquiryPeriod.THIS_MONTH;
        }
        return null;
    }

    private InquiryIntent intentOf(String compact) {
        if (containsAny(compact, UNPAID_TO_ME)) {
            return InquiryIntent.RECEIVE_SETTLEMENTS;
        }
        int sendAt = intentIndex(compact, SEND_STRONG, SEND_WEAK, SETTLEMENT_CONTEXT);
        int receiveAt = intentIndex(compact, RECEIVE_STRONG, RECEIVE_WEAK, SETTLEMENT_CONTEXT);
        if (containsAny(compact, DUE_KEYWORDS) && sendAt < 0 && receiveAt < 0) {
            return InquiryIntent.DUE_SETTLEMENTS;
        }
        if (sendAt >= 0 && (receiveAt < 0 || sendAt <= receiveAt)) {
            return InquiryIntent.SEND_SETTLEMENTS;
        }
        if (receiveAt >= 0) {
            return InquiryIntent.RECEIVE_SETTLEMENTS;
        }
        if (intentIndex(compact, EXPENSE_STRONG, EXPENSE_WEAK, EXPENSE_CONTEXT) >= 0) {
            return InquiryIntent.EXPENSE_SUMMARY;
        }
        if (intentIndex(compact, STATUS_STRONG, STATUS_WEAK, SETTLEMENT_CONTEXT) >= 0) {
            return InquiryIntent.SETTLEMENT_STATUS;
        }
        if (containsAny(compact, GUIDE_KEYWORDS)) {
            return InquiryIntent.GUIDE;
        }
        return InquiryIntent.UNKNOWN;
    }

    /**
     * 강한 표현은 그대로 보고, 약한 표현은 정산 맥락 단어가 함께 있을 때만 본다.
     * 둘 다 있으면 먼저 나온 위치를 돌려준다. 보낼/받을 선후 판단이 이 위치를 쓴다.
     */
    private int intentIndex(String compact, List<String> strong, List<String> weak, List<String> context) {
        int strongAt = earliestIndex(compact, strong);
        if (!containsAny(compact, context)) {
            return strongAt;
        }
        int weakAt = earliestIndex(compact, weak);
        if (strongAt < 0) {
            return weakAt;
        }
        return weakAt < 0 ? strongAt : Math.min(strongAt, weakAt);
    }

    private String titleHint(String question, String spaceless, String compact) {
        if (spaceless.length() != compact.length()) {
            return null;
        }
        boolean[] grammar = grammarMask(compact);
        boolean[] wordStarts = wordStarts(question, compact.length());
        Matcher matcher = TITLE_SUFFIX.matcher(compact);
        while (matcher.find()) {
            String candidate = titleAt(spaceless, compact, grammar, wordStarts, matcher.start(), matcher.end());
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 접미사(모임/정산) 앞을 문법 구간에 닿을 때까지만 거슬러 올라간다.
     * 문자열을 잘라내지 않으므로 후보는 항상 원문의 연속 부분 문자열이다.
     * 후보가 어절 첫 글자에서 시작하지 않으면 조사 조각이므로 버린다.
     */
    private String titleAt(String spaceless, String compact, boolean[] grammar, boolean[] wordStarts,
                           int suffixStart, int suffixEnd) {
        int start = suffixStart;
        while (start > 0
                && !grammar[start - 1]
                && suffixStart - (start - 1) <= MAX_TITLE_PREFIX
                && isNameCharacter(compact.charAt(start - 1))) {
            start--;
        }
        String prefix = compact.substring(start, suffixStart);
        if (prefix.length() < MIN_TITLE_PREFIX
                || !wordStarts[start]
                || containsAny(prefix, TITLE_STOP_WORDS)
                || TITLE_PARTICLE_TOKENS.contains(lastWord(compact, wordStarts, start, suffixStart))) {
            return null;
        }
        return spaceless.substring(start, suffixEnd);
    }

    /** 후보 앞부분에서 마지막 어절만 잘라낸다. */
    private String lastWord(String compact, boolean[] wordStarts, int start, int end) {
        int last = start;
        for (int i = start; i < end; i++) {
            if (wordStarts[i]) {
                last = i;
            }
        }
        return compact.substring(last, end);
    }

    /** 공백을 지운 문자열에서, 원문의 어절이 시작하는 위치를 표시한다. */
    private boolean[] wordStarts(String question, int length) {
        boolean[] starts = new boolean[length];
        boolean afterSpace = true;
        int index = 0;
        for (int i = 0; i < question.length() && index < length; i++) {
            char value = question.charAt(i);
            if (isRegexSpace(value)) {
                afterSpace = true;
                continue;
            }
            starts[index] = afterSpace;
            afterSpace = false;
            index++;
        }
        return starts;
    }

    /** {@code replaceAll("\\s+", "")}가 지우는 문자와 같은 집합이어야 위치가 어긋나지 않는다. */
    private boolean isRegexSpace(char value) {
        return value == ' ' || value == '\t' || value == '\n'
                || value == '\u000B' || value == '\f' || value == '\r';
    }

    /** 문법 어휘가 차지한 문자 위치를 표시한다. 제목 후보는 이 구간을 넘지 못한다. */
    private boolean[] grammarMask(String compact) {
        boolean[] mask = new boolean[compact.length()];
        for (String phrase : grammarPhrases()) {
            String needle = compactPhrase(phrase);
            if (needle.isEmpty()) {
                continue;
            }
            int from = compact.indexOf(needle);
            while (from >= 0) {
                for (int i = from; i < from + needle.length(); i++) {
                    mask[i] = true;
                }
                from = compact.indexOf(needle, from + 1);
            }
        }
        return mask;
    }

    /** 맥락 단어(정산·모임 등)는 제목의 일부이므로 문법에 넣지 않는다. */
    private List<String> grammarPhrases() {
        List<String> phrases = new ArrayList<>();
        phrases.addAll(PERIOD_THIS_WEEK);
        phrases.addAll(PERIOD_THIS_MONTH);
        phrases.addAll(PERIOD_LAST_MONTH);
        phrases.addAll(TIME_WORDS);
        phrases.addAll(SEND_STRONG);
        phrases.addAll(SEND_WEAK);
        phrases.addAll(RECEIVE_STRONG);
        phrases.addAll(RECEIVE_WEAK);
        phrases.addAll(UNPAID_TO_ME);
        phrases.addAll(EXPENSE_STRONG);
        phrases.addAll(EXPENSE_WEAK);
        phrases.addAll(STATUS_STRONG);
        phrases.addAll(STATUS_WEAK);
        phrases.addAll(DUE_KEYWORDS);
        phrases.addAll(GUIDE_KEYWORDS);
        phrases.addAll(FILLER_KEYWORDS);
        return phrases;
    }

    private boolean isNameCharacter(char value) {
        return (value >= '가' && value <= '힣')
                || (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }

    private boolean containsAny(String compact, List<String> phrases) {
        return earliestIndex(compact, phrases) >= 0;
    }

    private int earliestIndex(String compact, List<String> phrases) {
        int earliest = -1;
        for (String phrase : phrases) {
            int index = compact.indexOf(compactPhrase(phrase));
            if (index >= 0 && (earliest < 0 || index < earliest)) {
                earliest = index;
            }
        }
        return earliest;
    }

    private boolean usesTitleFilter(InquiryIntent intent) {
        return intent == InquiryIntent.SEND_SETTLEMENTS
                || intent == InquiryIntent.RECEIVE_SETTLEMENTS
                || intent == InquiryIntent.SETTLEMENT_STATUS
                || intent == InquiryIntent.EXPENSE_SUMMARY
                || intent == InquiryIntent.DUE_SETTLEMENTS;
    }

    private String compactPhrase(String phrase) {
        return phrase.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public record ParsedInquiry(InquiryIntent intent, InquiryPeriod period, String titleHint) {
    }
}
