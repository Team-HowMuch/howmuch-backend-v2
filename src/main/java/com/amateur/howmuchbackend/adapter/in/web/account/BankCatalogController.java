package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankCatalogV3WebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v3/banks")
@Tag(name = "금융기관 카탈로그")
public class BankCatalogController {
    private final GetBankCatalogUseCase catalog;

    @GetMapping
    @Operation(
            summary = "활성 은행 카탈로그 조회",
            description = "활성 은행 전체를 인증 수준과 함께 반환합니다. 인증 수준은 1=공동인증서만, 2=공동인증서+ID/PW, 3=공동인증서+ID/PW+계좌정보입니다.")
    public ApiResponse<List<BankCatalogV3WebResponse>> getBanks() {
        return ApiResponse.success(
                catalog.getAllBanks().stream().map(BankCatalogV3WebResponse::from).toList());
    }
}
