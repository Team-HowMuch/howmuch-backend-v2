package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWidgetSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "정산 API")
public interface SettlementWidgetDocs {
    @Operation(summary = "홈 위젯 정산 요약 조회", security = @SecurityRequirement(name = "bearerAuth"),
            description = "ACCESS 또는 WIDGET 토큰으로 본인의 REQUESTED·AWAITING_DEPOSIT 정산 전체를 조회합니다. "
                    + "receivable은 생성자 정산, payable은 생성자를 제외한 본인 참여 정산이며 초안·중단·완료·삭제는 제외합니다. "
                    + "각 탭은 합계·전체 건수·최신 생성순 목록을 제공합니다. 같은 사람이 여러 정산에서 미납이면 각각 셉니다. "
                    + "선택 중 품목 정산의 받을 금액은 상세와 같은 예상액이고 amountUnconfirmed=true입니다. "
                    + "낼 금액은 확인 입금을 차감한 본인 Share 잔액이며 미확정이면 null입니다. "
                    + "PAYABLE은 송금 가능, SELECTION_REQUIRED는 몫 선택 미제출, WAITING은 확정 대기 또는 납부 완료입니다. "
                    + "0원/납부 완료 참여도 진행 정산이면 목록에 남습니다. 빈 목록의 합계와 건수는 0입니다. "
                    + "lastReconciledAt은 마지막 성공한 위젯 전체 대조의 조회 기준 UTC 시각이며 최초·기록 만료 시 null입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "요약 조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 세션 인증 필요")
    })
    ApiResponse<SettlementWidgetSummaryWebResponse> getSummary();

    @Operation(summary = "홈 위젯 전체 입금 대조", security = @SecurityRequirement(name = "bearerAuth"),
            description = "본인이 생성한 진행 정산 전체의 입금을 대조한 뒤 요약 조회와 같은 응답을 반환합니다. "
                    + "REQUESTED에서도 이미 확정된 몫만 대조합니다. 같은 계좌는 조회를 공유합니다. "
                    + "동시 요청·새로고침 종료 후 60초 이내 재요청은 429이며 실패에도 제한을 유지합니다. "
                    + "일부 대조가 성공한 뒤 오류가 나면 성공한 입금 반영은 유지하고 전체 성공 시각은 갱신하지 않습니다. "
                    + "대조할 생성자 정산이 없으면 CODEF 조회 없이 요약만 반환합니다. 은행 조회는 동기 처리입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "전체 대조 및 요약 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "UNAUTHORIZED - 세션 인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "금융 연결 또는 정산 대조 데이터 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "CONCURRENT_UPDATE - 조회 중 정산 변경"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "TOO_MANY_REQUEST - 새로고침 처리 중 또는 60초 제한"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "TEMPORARILY_UNAVAILABLE - 대조 잠금 만료 또는 외부 조회 실패")
    })
    ApiResponse<SettlementWidgetSummaryWebResponse> refresh();
}
