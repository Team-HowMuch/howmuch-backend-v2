package com.amateur.howmuchbackend.application.port.out.treasurer;

import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;

import java.util.Optional;
import java.util.UUID;

public interface LoadTreasurerAnswerPort {
    /**
     * 신고 대상 답변을 본인 것으로 확인해 불러온다.
     *
     * <p>남의 답변이면 비어 있는 값을 돌려준다. 호출자는 없는 답변과 구분하지 않는다.
     */
    Optional<SavedTreasurerAnswer> findOwned(UUID answerId, Long userId);
}
