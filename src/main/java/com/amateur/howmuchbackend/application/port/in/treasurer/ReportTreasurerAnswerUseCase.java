package com.amateur.howmuchbackend.application.port.in.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.ReportTreasurerAnswerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerReportResult;

public interface ReportTreasurerAnswerUseCase {
    TreasurerAnswerReportResult report(ReportTreasurerAnswerCommand command);
}
