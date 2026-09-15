package com.amateur.howmuchbackend.application.port.in.treasurer.command;

import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReportReason;

import java.util.UUID;

public record ReportTreasurerAnswerCommand(
        UUID answerId,
        TreasurerAnswerReportReason reason,
        String detail
) {
}
