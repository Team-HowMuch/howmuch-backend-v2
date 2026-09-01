package com.amateur.howmuchbackend.adapter.in.web.account.dto.request;

import com.amateur.howmuchbackend.application.port.in.account.command.ReplaceAccountDisplayOrdersCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReplaceAccountDisplayOrdersWebRequest(
        @NotEmpty
        @Schema(
                description = "표시할 순서대로 나열한 활성 계좌 ID. 배열 순서가 곧 0부터의 표시 순서다.",
                example = "[11, 12, 10]"
        )
        List<@NotNull Long> accountIds
) {
    public ReplaceAccountDisplayOrdersCommand toCommand() {
        return new ReplaceAccountDisplayOrdersCommand(accountIds);
    }
}
