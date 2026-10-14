package com.amateur.howmuchbackend.application.treasurer.model;

import java.util.List;

/**
 * 사용법 질문에 대한 LLM 응답. 서버가 검사하기 전의 날것이다.
 *
 * @param route 사용법 질문이면 {@link Route#GUIDE}, 내 정산 숫자를 묻는 질문이면 {@link Route#DATA},
 *              얼마와 관계없는 질문이면 {@link Route#UNRELATED}
 * @param answer 사용자에게 보여 줄 답변 문장
 * @param sources 답변 근거로 든 서비스 안내 문서의 ID
 * @param followUps 이어서 물어볼 만한 질문
 */
public record TreasurerGuideReply(
        Route route,
        String answer,
        List<String> sources,
        List<String> followUps
) {
    public TreasurerGuideReply {
        sources = sources == null ? List.of() : List.copyOf(sources);
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
    }

    public enum Route {
        GUIDE,
        DATA,
        UNRELATED
    }
}
