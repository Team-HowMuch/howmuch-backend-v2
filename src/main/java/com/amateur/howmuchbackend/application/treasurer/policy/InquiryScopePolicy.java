package com.amateur.howmuchbackend.application.treasurer.policy;

import com.amateur.howmuchbackend.application.treasurer.model.InquiryScope;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

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

    public InquiryScope classify(String question) {
        if (question == null || question.isBlank()) {
            return InquiryScope.UNRELATED;
        }
        String compact = question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (containsAny(compact, SETTLEMENT_TERMS)
                || containsAny(compact, MONEY_TERMS)
                || containsAny(compact, GROUP_TERMS)
                || containsAny(compact, SCHEDULE_TERMS)
                || containsAny(compact, SERVICE_TERMS)) {
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
