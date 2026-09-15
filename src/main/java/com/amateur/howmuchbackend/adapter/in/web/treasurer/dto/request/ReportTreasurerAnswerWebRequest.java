package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.ReportTreasurerAnswerCommand;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReportReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ReportTreasurerAnswerWebRequest(
        @NotNull(message = "신고 사유를 선택해 주세요.")
        @Schema(
                description = "신고 사유",
                example = "INACCURATE",
                allowableValues = {"INACCURATE", "OFFENSIVE", "PRIVACY", "OTHER"}
        )
        TreasurerAnswerReportReason reason,

        @Size(max = 500, message = "신고 상세는 500자 이하여야 합니다.")
        @Schema(description = "신고 상세. 선택 항목입니다.", example = "금액이 실제와 달라요.")
        String detail
) {
    public ReportTreasurerAnswerCommand toCommand(UUID answerId) {
        return new ReportTreasurerAnswerCommand(answerId, reason, detail);
    }
}
