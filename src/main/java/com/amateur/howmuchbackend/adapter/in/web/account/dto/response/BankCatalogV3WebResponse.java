package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record BankCatalogV3WebResponse(
        @Schema(description = "CODEF 은행 기관코드", example = "0020")
        String organization,

        @Schema(description = "은행명", example = "우리은행")
        String name,

        @Schema(description = "은행 아이콘 URL")
        String iconUrl,

        @Schema(
                description = "연결 인증 수준: 1=공동인증서만, 2=공동인증서+ID/PW, 3=공동인증서+ID/PW+계좌정보",
                example = "3"
        )
        int authenticationLevel
) {
    public static BankCatalogV3WebResponse from(BankCatalogResult result) {
        return new BankCatalogV3WebResponse(
                result.organization(), result.name(), result.iconUrl(), result.authenticationLevel());
    }
}
