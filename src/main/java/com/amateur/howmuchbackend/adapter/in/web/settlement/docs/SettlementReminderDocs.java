package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementBulkReminderWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementReminderWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "정산 API", description = "정산 관련 API")
public interface SettlementReminderDocs {
    @Operation(
            summary = "정산 참여자 독촉",
            description = "정산 생성자가 REQUESTED 품목별 정산의 품목 미선택 앱 회원 또는 AWAITING_DEPOSIT 정산의 입금 전 앱 회원에게 알림함과 푸시 Outbox를 저장합니다. "
                    + "FCM 발송은 비동기로 처리하며 토큰이 없어도 저장합니다. "
                    + "동일 참여자는 성공 요청 후 30분 동안 다시 독촉할 수 없습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 독촉 알림 저장 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_PARTICIPANT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_REMINDER_NOT_AVAILABLE 또는 SETTLEMENT_REMINDER_TARGET_NOT_UNPAID"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "SETTLEMENT_REMINDER_TARGET_NOT_LINKED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "SETTLEMENT_REMINDER_COOLDOWN_ACTIVE - 30분 재요청 제한"
            )
    })
    ApiResponse<SettlementReminderWebResponse> remind(
            UUID settlementId,
            UUID participantId
    );

    @Operation(
            summary = "정산 참여자 일괄 독촉",
            description = "정산 생성자가 REQUESTED 품목별 정산의 품목 미선택 앱 회원 또는 AWAITING_DEPOSIT 정산의 입금 전 앱 회원 전체에게 알림을 생성합니다. "
                    + "sentCount는 FCM 수락 수가 아닌 알림 생성 수입니다. 미연결·비활성 회원·이미 품목 선택·일부 입금·30분 제한 대상은 건너뜁니다. "
                    + "nextAvailableAt은 관련 참여자별 활성 쿨다운 만료 시각 중 가장 늦은 UTC 시각이며, "
                    + "offset 없는 값을 UTC로 해석합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "일괄 독촉 처리 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED - 정산 생성자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "SETTLEMENT_REMINDER_NOT_AVAILABLE - 품목 선택 또는 입금 대기 상태가 아님"
            )
    })
    ApiResponse<SettlementBulkReminderWebResponse> remindAll(UUID settlementId);
}
