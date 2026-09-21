package com.amateur.howmuchbackend.application.port.out.treasurer;

import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReport;

public interface SaveTreasurerAnswerReportPort {
    /**
     * 신고를 남긴다.
     *
     * @return 저장하면 {@code true}, 이미 그 답변에 신고가 있으면 {@code false}
     */
    boolean saveIfAbsent(TreasurerAnswerReport report);
}
