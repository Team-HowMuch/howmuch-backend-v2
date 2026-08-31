package com.amateur.howmuchbackend.adapter.in.web.account.docs;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ConnectBankWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.LinkBankAccountsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankCatalogWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.LinkedAccountsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountDetailWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.AccountTransactionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.domain.account.AccountSort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.YearMonth;
import java.util.List;

@Tag(name = "계좌 API", description = "CODEF 기반 은행 연결과 계좌 연동 API")
public interface AccountDocs {
    @Operation(
            summary = "연결 계좌 목록 조회",
            description = "활성 계좌를 금융기관으로 필터링하고 기본 계좌 우선 또는 최근 사용순으로 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<List<AccountSummaryWebResponse>> getAccounts(String organization, AccountSort sort);

    @Operation(
            summary = "연결 계좌 상세 조회",
            description = "계좌 표시 정보와 요청 월의 소비 금액, 소비 건수, 최근 사용일을 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<AccountDetailWebResponse> getAccountDetail(Long accountId, YearMonth yearMonth);

    @Operation(
            summary = "계좌 거래내역 조회",
            description = "요청 월의 저장된 거래를 최신순으로 반환하며 최초 조회 시 CODEF에서 적재합니다. "
                    + "각 거래에는 현재 사용자가 생성한 연결 정산의 ID, 제목, 상태를 배열로 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<List<AccountTransactionWebResponse>> getAccountTransactions(Long accountId, YearMonth yearMonth);

    @Operation(
            summary = "연결 계좌 삭제",
            description = "활성 계좌를 로컬에서 삭제합니다. CODEF 기관 연결은 유지되며 DRAFT 정산이 수령 계좌로 사용 중이면 거부합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "ACCOUNT_IN_USE_BY_DRAFT_SETTLEMENT"
            )
    })
    ApiResponse<Void> deleteAccount(Long accountId);

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
