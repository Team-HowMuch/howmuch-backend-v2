package com.amateur.howmuchbackend.adapter.in.web.account.docs;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ConnectBankWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.LinkBankAccountsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankCatalogWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.LinkedAccountsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "계좌 API", description = "CODEF 기반 은행 연결과 계좌 연동 API")
public interface AccountDocs {
    @Operation(
            summary = "연동 가능한 은행 목록 조회",
            description = "활성 은행의 CODEF 기관코드, 이름, 아이콘 URL을 정렬 순서대로 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<List<BankCatalogWebResponse>> getBanks();

    @Operation(
            summary = "은행 연결 및 계좌 후보 조회",
            description = "은행 ID/PW를 CODEF에 연결하고 마스킹된 계좌 후보를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<BankConnectionWebResponse> connectBank(ConnectBankWebRequest request);

    @Operation(
            summary = "외부 계좌 연동",
            description = "은행 후보를 다시 조회해 선택 Key를 검증한 뒤 Account로 저장합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<LinkedAccountsWebResponse> linkAccounts(LinkBankAccountsWebRequest request);
}
