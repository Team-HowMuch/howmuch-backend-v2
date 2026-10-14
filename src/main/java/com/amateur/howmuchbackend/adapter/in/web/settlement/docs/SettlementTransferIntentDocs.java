package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementTransferIntentWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

@Tag(name = "정산 송금 신호 API")
public interface SettlementTransferIntentDocs {
    String DESCRIPTION = "송금 버튼에서 은행 앱을 열기 직전에 호출합니다. 요청 body는 없습니다. "
            + "본인의 미납 Share가 있는 REQUESTED/AWAITING_DEPOSIT 정산만 접수합니다. "
            + "30초 후 입금을 대조하며 같은 정산의 추가 탭은 기존 예약에 합쳐집니다. "
            + "미납이면 90초·300초 간격으로 최대 3회, 최초 탭부터 10분 이내에서 대조합니다. "
            + "처리 중이면 status=PROCESSING, scheduledAt=null입니다. 접수는 송금 완료를 의미하지 않습니다. "
            + "새 입금 푸시의 settlementId로 상세 캐시와 위젯 요약을 다시 조회하세요. "
            + "SETTLEMENT_DEPOSIT_CONFIRMED는 입금 확인, SETTLEMENT_COMPLETED는 전체 완료입니다. "
            + "완납·선택 미완료·중단·종료 정산에는 자동 대조를 예약하지 않습니다.";

    @Operation(summary = "회원 참여자의 송금 버튼 신호 접수", description = DESCRIPTION,
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202", description="예약 접수 또는 기존 예약 병합", useReturnTypeSchema=true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401", description="로그인 세션이 없거나 만료됨. 위젯 토큰은 사용할 수 없음"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403", description="SETTLEMENT_ACCESS_DENIED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="SETTLEMENT_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="SETTLEMENT_TRANSFER_INTENT_NOT_AVAILABLE"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="429", description="SETTLEMENT_TRANSFER_INTENT_COOLDOWN — 종료된 대조의 최초 탭부터 10분 미경과")
    })
    ApiResponse<SettlementTransferIntentWebResponse> recordMyIntent(@Parameter(description="정산 ID") UUID settlementId);

    @Operation(summary = "링크 참여자의 송금 버튼 신호 접수", description = DESCRIPTION
            + " 링크 token과 Link-Participant-Context가 같은 정산·링크에 속하고 모두 유효해야 합니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="202", description="예약 접수 또는 기존 예약 병합", useReturnTypeSchema=true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="400", description="Link-Participant-Context 헤더 누락 또는 잘못된 UUID"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="404", description="SETTLEMENT_LINK_NOT_FOUND / SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND / SETTLEMENT_PARTICIPANT_NOT_FOUND / SETTLEMENT_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409", description="SETTLEMENT_TRANSFER_INTENT_NOT_AVAILABLE"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="429", description="SETTLEMENT_TRANSFER_INTENT_COOLDOWN")
    })
    ApiResponse<SettlementTransferIntentWebResponse> recordIntent(@Parameter(description="정산 링크 token") String token,
            @Parameter(description="Link-Participant-Context 헤더의 참여 문맥 UUID") UUID participantContext);
}
