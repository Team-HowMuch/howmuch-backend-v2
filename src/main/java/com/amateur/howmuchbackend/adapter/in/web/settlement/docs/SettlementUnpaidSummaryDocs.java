package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementUnpaidSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "정산 API")
public interface SettlementUnpaidSummaryDocs {
    @Operation(
            summary = "미납 현황 집계 조회",
            description = "현재 로그인 회원이 생성한 REQUESTED·AWAITING_DEPOSIT 정산의 미납 현황을 반환합니다. "
                    + "초안·중단·완료·삭제 정산과 생성자 몫은 제외합니다. "
                    + "remainingReceivableAmount는 확정 부담액에서 확인 입금액을 차감한 Share별 잔액 합계입니다. "
                    + "unpaidParticipantCount는 잔액이 양수인 정산별 참여 건수이며, "
                    + "같은 사람이 여러 정산에서 미납이면 각각 셉니다. 비회원도 포함합니다. "
                    + "미확정 금액과 0원 부담액은 금액·미납 건수에 포함하지 않습니다. "
                    + "unconfirmedSettlementCount는 품목 배분이 완료되지 않은 REQUESTED 정산 수입니다. "
                    + "일부 부담액만 확정된 품목 정산은 확정 잔액과 미확정 정산 건수를 함께 반환합니다. "
                    + "집계 대상이 없으면 모든 필드를 0으로 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "본인 생성 정산의 미납 집계 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401", description = "UNAUTHORIZED - 인증 필요 또는 유효하지 않은 세션"
            )
    })
    ApiResponse<SettlementUnpaidSummaryWebResponse> getSummary();
}
