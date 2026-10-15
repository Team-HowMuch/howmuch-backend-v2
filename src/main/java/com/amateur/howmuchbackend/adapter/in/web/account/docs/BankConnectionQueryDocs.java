package com.amateur.howmuchbackend.adapter.in.web.account.docs;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankConnectionWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Tag(name = "계좌 API")
public interface BankConnectionQueryDocs {
    @Operation(summary = "내 금융사 연결 목록 조회",
            description = "사용자에게 귀속된 활성 금융사 연결을 계좌 선택 전에도 반환합니다. "
                    + "기관 연결 시각은 최초 생성 시각(UTC)이며 인증 방식과 CODEF connectedId 원문은 반환하지 않습니다. "
                    + "PC 인증 임시 연결은 포함하지 않으며 CODEF에 새 요청을 보내지 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<List<BankConnectionSummaryWebResponse>> getConnections();

    @Operation(summary = "기존 금융사 연결의 계좌 후보 조회",
            description = "내 활성 연결과 등록 기관을 검증한 뒤 CODEF 계좌 후보를 조회합니다. "
                    + "생년월일이 필요한 금융사는 birthDate를 전달합니다. 계좌번호·비밀번호는 서버의 저장 자격정보를 사용합니다. "
                    + "신규 금융사 로그인이나 계좌 저장을 수행하지 않습니다. 다른 사용자/비활성/구성 불일치 연결은 404, "
                    + "미등록 기관은 ACCOUNT_CANDIDATE_INVALID, 자격정보 누락은 BANK_ACCOUNT_RECONNECT_REQUIRED입니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<BankConnectionWebResponse> getConnectionAccounts(
            @Positive Long connectionId, @Pattern(regexp = "^\\d{4}$") String organization,
            @Pattern(regexp = "^(?:\\d{6}|\\d{8})$") String birthDate);
}
