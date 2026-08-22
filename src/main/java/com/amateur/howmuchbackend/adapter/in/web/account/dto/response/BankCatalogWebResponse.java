package com.amateur.howmuchbackend.adapter.in.web.account.dto.response;

import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record BankCatalogWebResponse(
        @Schema(description = "CODEF 은행 기관코드", example = "0020")
        String organization,

        @Schema(description = "은행명", example = "우리은행")
        String name,

        @Schema(description = "은행 아이콘 URL")
        String iconUrl
) {
    public static BankCatalogWebResponse from(BankCatalogResult result) {
        return new BankCatalogWebResponse(result.organization(), result.name(), result.iconUrl());
    }
}
