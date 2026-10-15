package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
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
    /**
     * 맥락 단어 뒤에 붙을 수 있는 조사와 어미.
     * 이 중 하나로 끝나야 그 어절이 맥락 단어다. "돈까스"의 "돈"은 맥락이 아니다.
     */
    private static final List<String> CONTEXT_SUFFIXES = List.of(
            "은", "는", "이", "가", "을", "를", "의", "에", "도", "만", "과", "와", "로", "나", "랑", "요",
            "으로", "에서", "에게", "한테", "부터", "까지", "이랑", "보다", "처럼", "밖에", "대로",
            "조차", "마저", "이나", "라도", "만큼", "께", "께서",
            "해야", "하는", "한", "할", "했", "하면", "해서", "하고"
    );

    /** 내가 낼 돈. 단독으로도 뜻이 분명하다 */
    private static final List<String> SEND_STRONG = List.of(
            "보낼", "낼정산", "보낼돈", "낼돈", "내야", "보내야", "송금"
    );
    /**
     * 부담·지불·미납 표현. 정산 맥락이 있어야 인정한다.
     * "안 냈"은 "숙제 안 냈어?" 처럼 돈과 무관하게도 쓰인다.
     */
    private static final List<String> SEND_WEAK = List.of(
            "줘야", "부담해야", "부담할", "지불해야", "안보낸", "안냈", "안낸"
    );

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

    /** 무엇부터 할지 묻는 표현. 단독으로도 뜻이 분명하다 */
    private static final List<String> PRIORITY_STRONG = List.of(
            "뭐부터", "무엇부터", "뭘먼저", "뭐먼저", "무엇을먼저", "무엇먼저", "어떤거부터", "어떤것부터",
            "어떤걸먼저", "어느거부터", "어느것부터", "우선순위"
    );
    /**
     * 먼저·추천·정리·급함 표현. 정산 맥락이 있어야 인정한다.
     * "몇 시부터 보내?", "회원가입 먼저 해야 해?", "영화 추천해줘"는 우선순위 질문이 아니다.
     */
    private static final List<String> PRIORITY_WEAK = List.of(
            "먼저처리", "먼저해야", "먼저보내", "정산부터", "돈부터", "추천", "정리", "급한", "급해", "밀린"
    );

    private static final List<String> DUE_KEYWORDS = List.of("마감해야 할 정산", "마감할 정산", "마감해야", "마감할", "마감");
    private static final List<String> GUIDE_KEYWORDS = List.of("ocr", "영수증");
    private static final List<String> FILLER_KEYWORDS = List.of("알려줘", "어떻게해", "어떻게", "누구야", "있어");

    private static final Pattern TITLE_SUFFIX = Pattern.compile("모임|정산");
    private static final List<String> TITLE_STOP_WORDS = List.of(
            "왜", "언제", "어디", "어떤", "무슨", "뭐", "누가", "누구", "얼마", "아직", "지금", "방금"
    );
    /**
     * 제목 바로 앞 어절이 이 중 하나면 제목이 아니다.
     * 조사·접속 조각과 지시·소유 대명사가 들어간다. "내 정산"의 "내"는 제목이 아니라 소유격이다.
     * 어절 단위로 정확히 비교한다. 부분 문자열로 보면 "이전모임" 같은 제목까지 걸린다.
     */
    private static final List<String> TITLE_STOP_TOKENS = List.of(
            "중에", "중에서", "그중", "안에", "에는", "에서", "말고", "건데",
            "부터", "까지", "동안", "이후", "이전", "대신",
            "내", "제", "우리", "그", "이", "저", "새", "다음", "지난"
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
        boolean[] wordStarts = wordStarts(question, compact.length());
        InquiryPeriod period = periodOf(compact);
        InquiryIntent intent = intentOf(compact, wordStarts);
        String titleHint = usesTitleFilter(intent)
                ? titleHint(spaceless, compact, wordStarts, intent)
                : null;
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

    private InquiryIntent intentOf(String compact, boolean[] wordStarts) {
        // "아직 송금 안 한 사람"의 "송금"은 보낼 정산이 아니다. 그 구간을 지운 문장으로 보낼 방향을 찾는다.
        int unpaidAt = earliestIndex(compact, UNPAID_TO_ME);
        String sendScope = unpaidAt < 0 ? compact : blankOut(compact, UNPAID_TO_ME);
        int sendAt = intentIndex(sendScope, wordStarts, SEND_STRONG, SEND_WEAK, SETTLEMENT_CONTEXT);
        int receiveAt = intentIndex(compact, wordStarts, RECEIVE_STRONG, RECEIVE_WEAK, SETTLEMENT_CONTEXT);
        if (unpaidAt >= 0) {
            receiveAt = receiveAt < 0 ? unpaidAt : Math.min(receiveAt, unpaidAt);
        }
        // 한쪽 방향만 물으면 그 방향 목록이 우선순위 순으로 나간다. 방향이 없거나 둘 다면 전체 우선순위다.
        // 마감·지출·진행 상태·영수증을 직접 말하면 그 의도가 이긴다. "지출 정리해줘"는 지출 요약이다.
        if ((sendAt < 0) == (receiveAt < 0)
                && asksPriority(compact, wordStarts)
                && !containsAny(compact, DUE_KEYWORDS)
                && !containsAny(compact, EXPENSE_STRONG)
                && !containsAny(compact, STATUS_STRONG)
                && !containsAny(compact, GUIDE_KEYWORDS)) {
            return InquiryIntent.PRIORITY_SETTLEMENTS;
        }
        if (unpaidAt >= 0) {
            return InquiryIntent.RECEIVE_SETTLEMENTS;
        }
        if (containsAny(compact, DUE_KEYWORDS) && sendAt < 0 && receiveAt < 0) {
            return InquiryIntent.DUE_SETTLEMENTS;
        }
        if (sendAt >= 0 && (receiveAt < 0 || sendAt <= receiveAt)) {
            return InquiryIntent.SEND_SETTLEMENTS;
        }
        if (receiveAt >= 0) {
            return InquiryIntent.RECEIVE_SETTLEMENTS;
        }
        if (containsAny(compact, EXPENSE_STRONG)) {
            return InquiryIntent.EXPENSE_SUMMARY;
        }
        if (containsAny(compact, STATUS_STRONG)) {
            return InquiryIntent.SETTLEMENT_STATUS;
        }
        // 안내 키워드는 강한 의도에는 지지만, 약한 일상어에는 지지 않는다.
        // "영수증 정산은 어떻게 처리해야 해?" 는 정산 상태가 아니라 안내 질문이다.
        if (containsAny(compact, GUIDE_KEYWORDS)) {
            return InquiryIntent.GUIDE;
        }
        if (hasContext(compact, wordStarts, EXPENSE_CONTEXT) && containsAny(compact, EXPENSE_WEAK)) {
            return InquiryIntent.EXPENSE_SUMMARY;
        }
        if (hasContext(compact, wordStarts, SETTLEMENT_CONTEXT) && containsAny(compact, STATUS_WEAK)) {
            return InquiryIntent.SETTLEMENT_STATUS;
        }
        return InquiryIntent.UNKNOWN;
    }

    /** 길이를 유지한 채 표현 구간을 지운다. 어절 시작 위치 배열을 그대로 쓰기 위해서다. */
    private String blankOut(String compact, List<String> phrases) {
        char[] chars = compact.toCharArray();
        for (String phrase : phrases) {
            String needle = compactPhrase(phrase);
            int from = compact.indexOf(needle);
            while (from >= 0) {
                Arrays.fill(chars, from, from + needle.length(), '#');
                from = compact.indexOf(needle, from + 1);
            }
        }
        return new String(chars);
    }

    private boolean asksPriority(String compact, boolean[] wordStarts) {
        return containsAny(compact, PRIORITY_STRONG)
                || hasContext(compact, wordStarts, SETTLEMENT_CONTEXT) && containsAny(compact, PRIORITY_WEAK);
    }

    /**
     * 강한 표현은 그대로 보고, 약한 표현은 정산 맥락 단어가 함께 있을 때만 본다.
     * 둘 다 있으면 먼저 나온 위치를 돌려준다. 보낼/받을 선후 판단이 이 위치를 쓴다.
     */
    private int intentIndex(String compact, boolean[] wordStarts,
                            List<String> strong, List<String> weak, List<String> context) {
        int strongAt = earliestIndex(compact, strong);
        if (!hasContext(compact, wordStarts, context)) {
            return strongAt;
        }
        int weakAt = earliestIndex(compact, weak);
        if (strongAt < 0) {
            return weakAt;
        }
        return weakAt < 0 ? strongAt : Math.min(strongAt, weakAt);
    }

    /** 맥락 단어는 어절 앞에서 시작하고 조사·어미로만 이어져야 한다. */
    private boolean hasContext(String compact, boolean[] wordStarts, List<String> context) {
        for (String term : context) {
            String needle = compactPhrase(term);
            int from = compact.indexOf(needle);
            while (from >= 0) {
                if (wordStarts[from] && endsWord(compact, wordStarts, from + needle.length())) {
                    return true;
                }
                from = compact.indexOf(needle, from + 1);
            }
        }
        return false;
    }

    /**
     * 어절이 여기서 끝나거나, 남은 부분이 조사·어미 하나이면 참이다.
     * 어절 끝의 문장부호는 세지 않는다. "금액은?" 의 남은 부분은 "은" 이다.
     */
    private boolean endsWord(String compact, boolean[] wordStarts, int end) {
        if (end >= compact.length() || wordStarts[end]) {
            return true;
        }
        int next = end;
        while (next < compact.length() && !wordStarts[next] && isNameCharacter(compact.charAt(next))) {
            next++;
        }
        String rest = compact.substring(end, next);
        return rest.isEmpty() || CONTEXT_SUFFIXES.contains(rest);
    }

    private String titleHint(String spaceless, String compact, boolean[] wordStarts, InquiryIntent intent) {
        if (spaceless.length() != compact.length()) {
            return null;
        }
        boolean[] grammar = grammarMask(compact, intent);
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
                || TITLE_STOP_TOKENS.contains(lastWord(compact, wordStarts, start, suffixStart))) {
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

    /**
     * 공백을 지운 문자열에서, 원문의 어절이 시작하는 위치를 표시한다.
     * 따옴표·괄호 같은 문장부호 다음도 어절 시작으로 본다. {@code "여행 모임"에서} 의 제목을 살린다.
     */
    private boolean[] wordStarts(String question, int length) {
        boolean[] starts = new boolean[length];
        boolean boundary = true;
        int index = 0;
        for (int i = 0; i < question.length() && index < length; i++) {
            char value = question.charAt(i);
            if (isRegexSpace(value)) {
                boundary = true;
                continue;
            }
            starts[index] = boundary;
            boundary = !isNameCharacter(value);
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
    private boolean[] grammarMask(String compact, InquiryIntent intent) {
        boolean[] mask = new boolean[compact.length()];
        for (String phrase : grammarPhrases(intent)) {
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

    /**
     * 맥락 단어(정산·모임 등)는 제목의 일부이므로 문법에 넣지 않는다.
     * 우선순위 표현은 우선순위 질문에서만 문법으로 본다. 다른 의도에서 "정리모임" 같은 제목을 지우지 않는다.
     */
    private List<String> grammarPhrases(InquiryIntent intent) {
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
        if (intent == InquiryIntent.PRIORITY_SETTLEMENTS) {
            phrases.addAll(PRIORITY_STRONG);
            phrases.addAll(PRIORITY_WEAK);
        }
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
                || intent == InquiryIntent.DUE_SETTLEMENTS
                || intent == InquiryIntent.PRIORITY_SETTLEMENTS;
    }

    private String compactPhrase(String phrase) {
        return phrase.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    public record ParsedInquiry(InquiryIntent intent, InquiryPeriod period, String titleHint) {
    }
}
