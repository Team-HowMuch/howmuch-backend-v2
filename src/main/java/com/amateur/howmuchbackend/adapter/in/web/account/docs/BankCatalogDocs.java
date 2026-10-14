package com.amateur.howmuchbackend.adapter.in.web.account.docs;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankCatalogV3WebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "금융기관 카탈로그")
public interface BankCatalogDocs {
    @Operation(
            summary = "활성 은행 카탈로그 조회",
            description = "활성 은행 전체를 인증 수준과 함께 반환합니다. 인증 수준은 1=공동인증서만, 2=공동인증서+ID/PW, 3=공동인증서+ID/PW+계좌정보입니다.")
    ApiResponse<List<BankCatalogV3WebResponse>> getBanks();
}
