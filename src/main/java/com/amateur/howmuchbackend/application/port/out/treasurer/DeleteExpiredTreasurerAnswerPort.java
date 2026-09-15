package com.amateur.howmuchbackend.application.port.out.treasurer;

import java.time.LocalDateTime;

public interface DeleteExpiredTreasurerAnswerPort {
    /**
     * 기준 시각보다 오래된 답변 중 신고가 달리지 않은 것만 지운다.
     *
     * <p>신고가 달린 답변은 스키마의 RESTRICT가 막으므로 조건에서도 명시적으로 제외한다.
     *
     * @return 지운 건수
     */
    int deleteExpired(LocalDateTime createdBefore, int batchSize);
}
