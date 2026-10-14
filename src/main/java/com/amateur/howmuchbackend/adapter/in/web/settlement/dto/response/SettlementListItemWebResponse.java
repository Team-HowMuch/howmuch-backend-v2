package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {
        "settlementId", "status", "requestedAt", "endedAt", "completedAt", "totalAmount", "participantCount",
        "payableParticipantCount", "completedParticipantCount", "amountToPay", "receivableAmount",
        "requesterName", "groups"
})
public record SettlementListItemWebResponse(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        @Schema(nullable = true, description = "공통 종료 시각. COMPLETED는 완료 시각, SUSPENDED는 현재 중단 시각이며 "
                + "그 외 상태는 null입니다. UTC 기준 offset 없는 값입니다.")
        LocalDateTime endedAt,
        @Schema(nullable = true, description = "기존 호환 필드. 완료 시각이며 COMPLETED 외 상태는 null입니다. "
                + "UTC 기준 offset 없는 값입니다.")
        LocalDateTime completedAt,
        @Schema(nullable = true, description = "RECEIVE 탭의 정산 총액. 생성자 본인 몫을 포함한다. "
                + "생성자가 받을 금액은 receivableAmount를 쓴다. PAY 탭에서는 null입니다.")
        Long totalAmount,
        @Schema(
                nullable = true,
                description = "배분 방식. 아직 안 정했으면 null이다. "
                        + "ITEM_ALLOCATION이면 참여자가 품목을 골라야 금액이 정해진다."
        )
        SettlementAllocationType allocationType,
        @Schema(nullable = true, description = "정산 참여자 전체 인원. 돈을 받는 생성자를 포함한다.")
        Integer participantCount,
        @Schema(
                nullable = true,
                description = "납부 대상 인원. 참여자 중 돈을 받는 생성자를 제외한 수이며 "
                        + "정산 상세의 payableParticipantCount와 항상 같다. 진행률의 분모로 쓴다. "
                        + "Share 생성 전(품목별 정산에서 전원 제출 전)에도 실제 인원을 반환한다."
        )
        Integer payableParticipantCount,
        @Schema(nullable = true, description = "납부를 마친 인원. 생성자를 제외한다.")
        Integer completedParticipantCount,
        @Schema(nullable = true, description = "PAY 탭의 확정 개인 납부 금액. "
                + "ITEM_ALLOCATION의 REQUESTED·SUSPENDED 상태에서 개인 금액이 미확정이면 "
                + "생성자 몫을 포함한 정산 총액을 목록 표시값으로 반환하며 실제 개인 납부액을 뜻하지 않습니다. "
                + "그 외 금액 미확정 상태와 RECEIVE 탭에서는 null입니다.")
        Long amountToPay,
        @Schema(nullable = true, description = "RECEIVE 탭에서 생성자가 받을 금액. 생성자 본인 몫을 뺀 참여자 납부 총액이며 "
                + "입금 여부와 무관하다. 정산 상세의 summary.expectedAmount와 같다. "
                + "ITEM_ALLOCATION의 REQUESTED·SUSPENDED 상태에서는 영수증 총액에서 생성자가 고른 몫을 뺀 값이다. "
                + "PAY 탭에서는 null입니다.")
        Long receivableAmount,
        @Schema(nullable = true)
        String requesterName,
        List<GroupSummary> groups
) {
    public static SettlementListItemWebResponse from(SettlementListItemResult result) {
        return new SettlementListItemWebResponse(
                result.settlementId(),
                result.status(),
                result.requestedAt(),
                result.endedAt(),
                result.completedAt(),
                result.totalAmount(),
                result.allocationType(),
                result.participantCount(),
                result.payableParticipantCount(),
                result.completedParticipantCount(),
                result.amountToPay(),
                result.receivableAmount(),
                result.requesterName(),
                result.groups().stream()
                        .map(GroupSummary::from)
                        .toList()
        );
    }

    @Schema(
            name = "SettlementListGroupSummaryWebResponse",
            requiredProperties = {"groupId", "groupName", "groupImageUrl"}
    )
    public record GroupSummary(
            Long groupId,
            String groupName,
            @Schema(nullable = true)
            String groupImageUrl
    ) {
        private static final String IMAGE_URL = "/api/v2/groups/%d/image?v=%s";

        private static GroupSummary from(SettlementListItemResult.GroupSummary result) {
            return new GroupSummary(
                    result.groupId(),
                    result.groupName(),
                    result.imageVersion() == null
                            ? null
                            : IMAGE_URL.formatted(result.groupId(), result.imageVersion())
            );
        }
    }
}
