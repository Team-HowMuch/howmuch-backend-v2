package com.amateur.howmuchbackend.adapter.in.web.settlement.netting;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.request.*;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;

@Tag(name = "그룹 압축 정산 API", description = "그룹 전체 미납 관계의 직접 압축·재압축과 비동기 실행. 실제 은행 송금은 수행하지 않습니다.")
@SecurityRequirement(name = "bearerAuth")
public interface GroupNettingDocs {
    @Operation(
            summary = "압축 정산 실행 접수",
            description =
                    "활성 그룹 멤버가 실행할 수 있습니다. Idempotency-Key(UUID)를 유지해 재시도하세요. HTTP 202는 접수이며 실행 상태를"
                        + " 조회해야 합니다. 새 미납 원본과 기존 ACTIVE 압축을 합쳐 2건 이상 필요하며 1건이면 409"
                        + " NETTING_INSUFFICIENT_SETTLEMENTS입니다. 은행 동기화 후 대상이 줄거나 입력이 변경되면 STALE로"
                        + " 종료됩니다.")
    ApiResponse<NettingExecutionWebResponse> execute(long groupId, UUID key);

    @Operation(
            summary = "압축 정산 작업 상태 조회",
            description =
                    "QUEUED, SYNCING, RETRY, SUCCEEDED, STALE, FAILED. 실패 시 errorCode에 서버 공통 코드가"
                            + " 제공됩니다.")
    ApiResponse<NettingExecutionWebResponse> getExecution(long groupId, UUID executionId);

    @Operation(
            summary = "압축 전 예상 송금 건수 조회",
            description =
                    "활성 그룹 멤버 전용. 현재 DB 미납으로 계산한 그룹 공통 건수를 기본 30초 캐시하며 입력 변경 커밋 후 무효화합니다."
                        + " afterTransferCount로 안내하고 canCompress로 활성 멤버의 압축 버튼을 제어합니다. reason은 불가 사유이며"
                        + " PROCESSING(진행 중) 우선, NOT_ENOUGH_TARGETS(대상 부족), NO_REDUCTION(건수 미감소), 가능하면 null입니다."
                        + " 미결 입금 검토는 기존 409 NETTING_ALREADY_ACTIVE를 유지합니다. 현재 합칠 정산이 2건"
                        + " 미만이면 false이며 기존 압축은 1건으로 셉니다. 실행 접수·미리보기 생성·은행 조회는 하지 않으며 확정 전 동기화에 따라"
                        + " 결과가 달라질 수 있습니다. 캐시 장애/장기 경합 시 503과"
                        + " NETTING_ESTIMATE_UNAVAILABLE/NETTING_ESTIMATE_BUSY를 반환합니다.")
    ApiResponse<GroupNettingEstimateWebResponse> estimate(long groupId);

    @Operation(summary = "그룹 압축 정산 이력 조회",
            description = "transfers.reminderNextAvailableAt은 송금별 다음 수동 독촉 가능 시각(UTC offset 없는 값)이며, 활성 쿨다운이 없으면 null입니다. reminderBlockedReason은 비활성·입금 검토·입금 확인 차단 사유이며 없으면 null입니다. 수취인 권한·쿨다운·압축 진행 제한은 별도로 적용합니다.")
    ApiResponse<List<GroupNettingWebResponse>> list(long groupId);

    @Operation(
            summary = "모든 멤버가 공유하는 최종 압축 정산 조회",
            description =
                    "일반 목록에서 숨긴 원본 정산의 상호명·조회자 카테고리·전체/납부/완료 인원을 sources로 제공합니다. transfers의"
                        + " payerName/receiverName 및 payerPublicId/receiverPublicId로 표시·프로필을 연결합니다. transfers.reminderNextAvailableAt은 다음 수동 독촉 가능 시각이며 활성 쿨다운이 없으면 null입니다. reminderBlockedReason으로 비활성·입금 검토·입금 확인 차단 사유를 제공합니다."
                        + " 원본 금액, 실제 입금과 대체 금액을 구분합니다. status(UNPAID/PARTIAL/PAID)·confirmedAt과 세대의 completedAt/supersededAt을 제공합니다. 시각은 UTC offset 없는 값입니다. sourceSettlementIds는 송금자 또는 수취인이 미납 채무 당사자인 관련 원본(선대 포함)이며 원본별 최종 송금 기여액이나 직접 채권 관계가 아닙니다.")
    ApiResponse<GroupNettingWebResponse> get(long groupId, UUID nettingId);

    @Operation(
            summary = "Current group netting",
            description =
                    "Returns the active or latest completed result; 404 when there is no history. transfers.reminderNextAvailableAt은 UTC offset 없는 다음 수동 독촉 가능 시각이며 활성 쿨다운이 없으면 null입니다. reminderBlockedReason은 비활성·입금 검토·입금 확인 차단 사유입니다.")
    ApiResponse<GroupNettingWebResponse> current(long groupId);

    @Operation(
            summary = "최종 송금 안내 조회",
            description =
                    "해당 송금자만 계좌 전체번호를 조회합니다. reference를 받는 분 통장 표시에 직접 입력해야 합니다. 송금 앱 링크가 표시값을 자동"
                            + " 입력하지 않습니다.")
    ApiResponse<NettedTransferInstructionWebResponse> instruction(
            long groupId, UUID nettingId, UUID transferId);

    @Operation(
            summary = "본인 수취 계좌 입금 확인 접수",
            description = "수취인 전용. HTTP 202로 비동기 접수합니다. 표시값이 없거나 금액이 모호하면 실제 거래 검토가 생성됩니다.")
    ApiResponse<NettingExecutionWebResponse> reconcile(long groupId, UUID nettingId, UUID key);

    @Operation(summary = "최종 송금 독촉", description = "수취인 전용. 30분 쿨다운은 기존 429 SETTLEMENT_REMINDER_COOLDOWN_ACTIVE입니다. "
            + "비활성 압축은 409 NETTING_REMINDER_NOT_ACTIVE, 열린 검토는 409 NETTING_REMINDER_DEPOSIT_REVIEW_OPEN, "
            + "일부/전액 입금 확인은 409 NETTING_REMINDER_DEPOSIT_RECEIVED로 거절합니다. "
            + "이 우선순위는 transfers.reminderBlockedReason과 같습니다. 압축 진행 중 NETTING_PROCESSING과 수취인 권한 제한은 유지합니다.")
    ApiResponse<Void> remind(long groupId, UUID nettingId, UUID transferId);
}
