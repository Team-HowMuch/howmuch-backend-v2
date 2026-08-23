package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementContactCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SettlementContactWebRequest(
        @NotBlank
        @Size(max = 100, message = "연락처 식별자는 100자 이하여야 합니다.")
        @Schema(description = "클라이언트 주소록 항목 식별자", example = "contact-42")
        String contactId,

        @NotBlank
        @Size(max = 100, message = "연락처 표시 이름은 100자 이하여야 합니다.")
        @Schema(description = "주소록에 저장된 표시 이름", example = "민선영")
        String displayName,

        @NotBlank
        @Size(max = 20, message = "전화번호는 20자 이하여야 합니다.")
        @Schema(description = "회원 매칭에 사용할 휴대폰 번호", example = "010-1234-5678")
        String phoneNumber
) {
    public SettlementContactCommand toCommand() {
        return new SettlementContactCommand(contactId, displayName, phoneNumber);
    }
}
