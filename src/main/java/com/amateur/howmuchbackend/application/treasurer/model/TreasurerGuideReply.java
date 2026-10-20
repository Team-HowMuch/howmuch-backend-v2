package com.amateur.howmuchbackend.application.treasurer.model;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;

import java.util.List;

/**
 * 질문에 대한 안내 LLM 응답. 서버가 검사하기 전의 날것이다.
 *
 * @param route 사용법 질문이면 {@link Route#GUIDE}, 내 정산 숫자를 묻는 질문이면 {@link Route#DATA},
 *              얼마와 관계없는 질문이면 {@link Route#UNRELATED}
 * @param answer 사용자에게 보여 줄 답변 문장
 * @param sources 답변 근거로 든 서비스 안내 문서의 ID
 * @param followUps 이어서 물어볼 만한 질문
 * @param intent 데이터 질문이 가리키는 의도. 어느 의도인지 정할 수 없다고 답했으면 {@link InquiryIntent#UNKNOWN},
 *               답하지 않았거나 정해진 값이 아니면 {@code null}. {@link InquiryIntent#GUIDE}는 오지 않는다.
 * @param period 질문이 말한 기간. 말하지 않았거나 정해진 값이 아니면 {@code null}
 * @param titleHint 질문이 가리키는 정산·모임 이름. 질문 원문에 있는지는 아직 확인하지 않았다.
 */
public record TreasurerGuideReply(
        Route route,
        String answer,
        List<String> sources,
        List<String> followUps,
        InquiryIntent intent,
        InquiryPeriod period,
        String titleHint
) {
    public TreasurerGuideReply {
        sources = sources == null ? List.of() : List.copyOf(sources);
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
        if (intent == InquiryIntent.GUIDE) {
            intent = null;
        }
    }

    public TreasurerGuideReply(Route route, String answer, List<String> sources, List<String> followUps) {
        this(route, answer, sources, followUps, null, null, null);
    }

    public enum Route {
        GUIDE,
        DATA,
        UNRELATED
    }
}
