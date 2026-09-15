package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerReportResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record TreasurerAnswerReportWebResponse(
        UUID reportId,
        LocalDateTime createdAt
) {
    public static TreasurerAnswerReportWebResponse from(TreasurerAnswerReportResult result) {
        return new TreasurerAnswerReportWebResponse(result.reportId(), result.createdAt());
    }
}
