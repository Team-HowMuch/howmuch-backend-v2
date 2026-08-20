package com.amateur.howmuchbackend.adapter.in.web.account.dto.request;

import com.amateur.howmuchbackend.application.port.in.account.command.LinkBankAccountsCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

public record LinkBankAccountsWebRequest(
        @NotNull(message = "연결 ID는 필수입니다.")
        @Positive(message = "연결 ID는 양수여야 합니다.")
        Long connectionId,

        @NotBlank(message = "기관코드는 필수입니다.")
        @Pattern(regexp = "^\\d{4}$", message = "기관코드는 4자리 숫자여야 합니다.")
        String organization,

        @NotEmpty(message = "연동할 계좌를 하나 이상 선택해야 합니다.")
        @Size(max = 50, message = "한 번에 50개까지 연동할 수 있습니다.")
        List<@NotBlank(message = "계좌 후보 Key는 비어 있을 수 없습니다.") String> candidateKeys,

        @Pattern(regexp = "^(?:\\d{6}|\\d{8})$", message = "생년월일은 6자리 또는 8자리 숫자여야 합니다.")
        @Schema(description = "기관이 요구하는 생년월일", example = "19990101", nullable = true)
        String birthDate
) {
    public LinkBankAccountsCommand toCommand() {
        return new LinkBankAccountsCommand(connectionId, organization, List.copyOf(candidateKeys), birthDate);
    }
}
