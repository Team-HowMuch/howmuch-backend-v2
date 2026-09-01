package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {
        "settlementId", "status", "requestedAt", "totalAmount", "participantCount",
        "payableParticipantCount", "completedParticipantCount", "amountToPay",
        "requesterName", "groups"
})
public record SettlementListItemWebResponse(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        @Schema(nullable = true)
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
        @Schema(nullable = true)
        Long amountToPay,
        @Schema(nullable = true)
        String requesterName,
        List<GroupSummary> groups
) {
    public static SettlementListItemWebResponse from(SettlementListItemResult result) {
        return new SettlementListItemWebResponse(
                result.settlementId(),
                result.status(),
                result.requestedAt(),
                result.totalAmount(),
                result.allocationType(),
                result.participantCount(),
                result.payableParticipantCount(),
                result.completedParticipantCount(),
                result.amountToPay(),
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
        private static final String IMAGE_URL = "/api/v2/groups/%d/image";

        private static GroupSummary from(SettlementListItemResult.GroupSummary result) {
            return new GroupSummary(
                    result.groupId(),
                    result.groupName(),
                    result.hasImage() ? IMAGE_URL.formatted(result.groupId()) : null
            );
        }
    }
}
