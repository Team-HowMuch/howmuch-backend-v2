package com.amateur.howmuchbackend.application.port.out.treasurer;

import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;

public interface SaveTreasurerAnswerPort {
    /**
     * 사용자에게 나간 답변을 남긴다.
     *
     * <p>질문 응답 흐름의 부수 효과다. 호출자는 실패를 삼키고 답변 자체는 내보낸다.
     */
    void save(SavedTreasurerAnswer answer);
}
