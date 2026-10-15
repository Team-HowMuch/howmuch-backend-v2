package com.amateur.howmuchbackend.application.port.out.treasurer;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerGuideReply;
import com.amateur.howmuchbackend.application.treasurer.model.TreasurerKnowledge;

import java.util.Optional;

public interface GenerateTreasurerGuidePort {
    /**
     * 서비스 안내 문서만 근거로 사용법 질문에 답한다.
     *
     * @return LLM을 쓸 수 없거나 응답을 읽지 못하면 비어 있다
     */
    Optional<TreasurerGuideReply> generate(String question, TreasurerKnowledge knowledge);
}
