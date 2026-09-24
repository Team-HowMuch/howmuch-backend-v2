package com.amateur.howmuchbackend.adapter.in.web.account.docs;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ConnectBankWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.LinkBankAccountsWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.ReplaceAccountDisplayOrdersWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.request.UpdateAccountDisplayOrderWebRequest;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import java.time.YearMonth;
import java.util.List;

@Tag(name = "계좌 API", description = "CODEF 기반 은행 연결과 계좌 연동 API. 0 이하 계좌 경로 ID는 INVALID_ARGUMENT(400)로 거부합니다.")
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
    ApiResponse<AccountDetailWebResponse> getAccountDetail(@Positive Long accountId, YearMonth yearMonth);

    @Operation(
            summary = "계좌 거래내역 조회",
            description = "요청 월의 저장된 거래를 최신순으로 반환하며 최초 조회 시 CODEF에서 적재합니다. "
                    + "각 거래에는 연결된 정산의 ID, 제목, 상태를 배열로 반환합니다. "
                    + "거래 원문은 description에 유지하고 화면 표시용 거래처명과 거래 유형을 display로 반환합니다. "
                    + "결제 내역으로 가져온 지출, 수금 계좌에 대조된 입금, 참여자 납부로 확인된 송금이 연결 대상이며 "
                    + "작성 중(DRAFT) 정산은 제외합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502",
                    description = "CODEF_UNMAPPED_ERROR 또는 CODEF_RESPONSE_INVALID - CODEF 응답 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "CODEF_UPSTREAM_UNAVAILABLE - CODEF 조회 실패"
            )
    })
    ApiResponse<List<AccountTransactionWebResponse>> getAccountTransactions(@Positive Long accountId, YearMonth yearMonth);

    @Operation(
            summary = "연결 계좌 삭제",
            description = "활성 계좌를 로컬에서 삭제합니다. CODEF 기관 연결은 유지되며 진행 중인 정산이 수령 계좌로 사용 중이면 거부합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "ACCOUNT_IN_USE_BY_SETTLEMENT"
            )
    })
    ApiResponse<Void> deleteAccount(@Positive Long accountId);

    @Operation(
            summary = "기본 결제수단 설정",
            description = "소유한 활성 계좌를 기본 결제수단으로 설정하고 기존 기본 계좌를 해제합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    })
    ApiResponse<Void> setDefaultAccount(@Positive Long accountId);

    @Operation(
            summary = "계좌 표시 순서 일괄 저장",
            description = "소유한 활성 계좌 전체의 순서를 한 트랜잭션에서 저장합니다. "
                    + "배열 순서가 곧 0부터의 표시 순서이며, 하나라도 어긋나면 아무것도 저장되지 않습니다. "
                    + "활성 계좌가 빠지거나 중복되거나 소유하지 않은 계좌가 있으면 "
                    + "INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST를 반환합니다. "
                    + "기본 계좌 여부는 표시 순서와 독립이라 배열 순서를 그대로 따릅니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ARGUMENT 또는 INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "TEMPORARILY_UNAVAILABLE - 계좌 잠금 대기 초과"
            )
    })
    ApiResponse<Void> replaceAccountDisplayOrders(
            ReplaceAccountDisplayOrdersWebRequest request
    );

    @Operation(
            summary = "계좌 표시 순서 변경 (단건 이동)",
            description = "소유한 활성 계좌를 0 기반 표시 위치로 이동하고 전체 순서를 다시 정렬합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_ACCOUNT_DISPLAY_ORDER"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND")
    })
    ApiResponse<Void> updateAccountDisplayOrder(
            @Positive Long accountId,
            @Valid UpdateAccountDisplayOrderWebRequest request
    );

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
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502",
                    description = "CODEF_UNMAPPED_ERROR 또는 CODEF_RESPONSE_INVALID - CODEF 응답 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "CODEF_UPSTREAM_UNAVAILABLE - CODEF 조회 실패"
            )
    })
    ApiResponse<BankConnectionWebResponse> connectBank(ConnectBankWebRequest request);

    @Operation(
            summary = "외부 계좌 연동",
            description = "은행 후보를 다시 조회해 선택 Key를 검증한 뒤 Account로 저장합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502",
                    description = "CODEF_UNMAPPED_ERROR 또는 CODEF_RESPONSE_INVALID - CODEF 응답 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "CODEF_UPSTREAM_UNAVAILABLE - CODEF 조회 실패"
            )
    })
    ApiResponse<LinkedAccountsWebResponse> linkAccounts(LinkBankAccountsWebRequest request);
}
