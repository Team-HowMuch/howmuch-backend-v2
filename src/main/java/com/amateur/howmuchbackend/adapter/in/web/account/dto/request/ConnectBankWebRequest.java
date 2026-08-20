package com.amateur.howmuchbackend.adapter.in.web.account.dto.request;

import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConnectBankWebRequest(
        @NotBlank(message = "기관코드는 필수입니다.")
        @Pattern(regexp = "^\\d{4}$", message = "기관코드는 4자리 숫자여야 합니다.")
        @Schema(description = "CODEF 은행 기관코드", example = "0020")
        String organization,

        @NotBlank(message = "금융기관 로그인 ID는 필수입니다.")
        @Size(max = 100, message = "금융기관 로그인 ID는 100자 이하여야 합니다.")
        @Schema(description = "금융기관 로그인 ID", accessMode = Schema.AccessMode.WRITE_ONLY)
        String loginId,

        @NotBlank(message = "금융기관 비밀번호는 필수입니다.")
        @Size(max = 200, message = "금융기관 비밀번호는 200자 이하여야 합니다.")
        @Schema(description = "금융기관 비밀번호", accessMode = Schema.AccessMode.WRITE_ONLY)
        String password,

        @Pattern(regexp = "^(?:\\d{6}|\\d{8})$", message = "생년월일은 6자리 또는 8자리 숫자여야 합니다.")
        @Schema(description = "기관이 요구하는 생년월일", example = "19990101", nullable = true)
        String birthDate
) {
    public ConnectBankCommand toCommand() {
        return new ConnectBankCommand(organization, loginId, password, birthDate);
    }

    @Override
    public String toString() {
        return "ConnectBankWebRequest[organization=%s, loginId=***, password=***, birthDate=***]"
                .formatted(organization);
    }
}
