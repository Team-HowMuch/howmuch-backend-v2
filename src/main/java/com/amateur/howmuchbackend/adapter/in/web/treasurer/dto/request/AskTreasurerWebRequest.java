package com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request;

import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AskTreasurerWebRequest(
        @NotBlank(message = "질문을 입력해 주세요.")
        @Size(max = 500, message = "질문은 500자 이하여야 합니다.")
        @Schema(description = "정산·지출 자연어 질문", example = "이번 달 내가 보낼 정산은 얼마야?")
        String question
) {
    public AskTreasurerCommand toCommand() {
        return new AskTreasurerCommand(question.trim());
    }
}
