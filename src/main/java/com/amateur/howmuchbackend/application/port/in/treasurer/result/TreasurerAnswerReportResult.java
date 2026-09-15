package com.amateur.howmuchbackend.application.port.in.treasurer.result;

import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReport;

import java.time.LocalDateTime;
import java.util.UUID;

public record TreasurerAnswerReportResult(
        UUID reportId,
        LocalDateTime createdAt
) {
    public static TreasurerAnswerReportResult from(TreasurerAnswerReport report) {
        return new TreasurerAnswerReportResult(report.id(), report.createdAt());
    }
}
