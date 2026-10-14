package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.application.treasurer.model.InquiryScope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class InquiryScopePolicy {
    private static final List<String> SETTLEMENT_TERMS = List.of(
            "정산", "더치페이", "더치", "송금", "입금", "납부", "회비", "지출", "결제", "비용"
    );
    private static final List<String> MONEY_TERMS = List.of(
            "돈", "금액", "얼마", "계좌", "이체"
    );
    private static final List<String> GROUP_TERMS = List.of(
            "모임", "그룹", "멤버", "참여자", "총무"
    );
    private static final List<String> SCHEDULE_TERMS = List.of(
            "기한", "마감", "독촉", "리마인드"
    );
    private static final List<String> SERVICE_TERMS = List.of(
            "이서비스", "이앱", "howmuch", "하우머치", "영수증", "ocr"
    );
    /**
     * 앱 사용법을 묻는 질문에 나오는 기능 이름. 정산 단어 없이도 서비스 안내 대상이다.
     * "로그인은 어떻게 해?"가 관련 없는 질문으로 거절되지 않게 한다.
     */
    private static final List<String> APP_FEATURE_TERMS = List.of(
            "로그인", "로그아웃", "회원가입", "탈퇴", "핀번호", "지문", "얼굴인증", "faceid", "알림",
            "방장", "초대코드", "초대링크", "리캡", "ai비서"
    );
    /**
     * 일상에서도 흔한 기능 이름. "와이파이 비밀번호", "유튜브 링크"처럼 앱과 무관하게 쓰이므로
     * 앱을 가리키는 단어가 함께 있을 때만 관련 질문으로 본다.
     */
    private static final List<String> AMBIGUOUS_APP_TERMS = List.of(
            "가입", "비밀번호", "초대", "프로필", "아이디", "토스", "링크", "약관", "비서"
    );
    private static final List<String> APP_CONTEXT_TERMS = List.of(
            "얼마", "앱", "어플", "정산", "그룹", "모임", "계좌", "송금", "입금"
    );
    /** "shopping"의 pin은 세지 않는다. 영문 단어 경계로 본다. */
    private static final Pattern PIN = Pattern.compile("(?<![a-z])pin(?![a-z])");

    public InquiryScope classify(String question) {
        if (question == null || question.isBlank()) {
            return InquiryScope.UNRELATED;
        }
        String compact = question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (containsAny(compact, SETTLEMENT_TERMS)
                || containsAny(compact, MONEY_TERMS)
                || containsAny(compact, GROUP_TERMS)
                || containsAny(compact, SCHEDULE_TERMS)
                || containsAny(compact, SERVICE_TERMS)
                || containsAny(compact, APP_FEATURE_TERMS)
                || PIN.matcher(question.toLowerCase(Locale.ROOT)).find()
                || (containsAny(compact, AMBIGUOUS_APP_TERMS) && containsAny(compact, APP_CONTEXT_TERMS))) {
            return InquiryScope.RELATED;
        }
        return InquiryScope.UNRELATED;
    }

    private boolean containsAny(String compact, List<String> terms) {
        for (String term : terms) {
            if (compact.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
