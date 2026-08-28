package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {
        "settlementId", "status", "requestedAt", "totalAmount", "participantCount",
        "completedParticipantCount", "amountToPay", "requesterName", "groups"
})
public record SettlementListItemWebResponse(
        UUID settlementId,
        SettlementStatus status,
        LocalDateTime requestedAt,
        @Schema(nullable = true)
        Long totalAmount,
        @Schema(nullable = true)
        Integer participantCount,
        @Schema(nullable = true)
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
                result.participantCount(),
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
