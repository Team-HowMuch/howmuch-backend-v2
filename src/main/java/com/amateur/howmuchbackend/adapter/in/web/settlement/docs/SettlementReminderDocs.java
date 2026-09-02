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
            summary = "미납 참여자 정산 독촉",
            description = "정산 생성자가 AWAITING_DEPOSIT 정산의 입금 전 앱 회원에게 푸시 알림을 보냅니다. "
                    + "동일 참여자는 성공 요청 후 30분 동안 다시 독촉할 수 없습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "정산 독촉 푸시 요청 성공",
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
                    description = "SETTLEMENT_REMINDER_TARGET_NOT_LINKED 또는 SETTLEMENT_REMINDER_PUSH_TARGET_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "429",
                    description = "SETTLEMENT_REMINDER_COOLDOWN_ACTIVE - 30분 재요청 제한"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "SETTLEMENT_REMINDER_DELIVERY_FAILED 또는 SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE"
            )
    })
    ApiResponse<SettlementReminderWebResponse> remind(
            UUID settlementId,
            UUID participantId
    );

    @Operation(
            summary = "미납 참여자 일괄 정산 독촉",
            description = "정산 생성자가 AWAITING_DEPOSIT 정산의 입금 전 앱 회원 전체에게 순차적으로 푸시 알림을 보내고 실제 성공 수를 반환합니다. "
                    + "미연결·일부 입금·푸시 대상 미등록·30분 제한 대상은 건너뜁니다. "
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
                    description = "SETTLEMENT_REMINDER_NOT_AVAILABLE - 입금 대기 상태가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "SETTLEMENT_REMINDER_DELIVERY_FAILED 또는 SETTLEMENT_REMINDER_DELIVERY_UNAVAILABLE"
            )
    })
    ApiResponse<SettlementBulkReminderWebResponse> remindAll(UUID settlementId);
}
