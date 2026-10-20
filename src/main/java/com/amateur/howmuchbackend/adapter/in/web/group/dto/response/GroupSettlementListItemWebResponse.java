package com.amateur.howmuchbackend.adapter.in.web.group.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.GroupSettlementListItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.GroupSettlementListItemResult.ViewerRole;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(requiredProperties = {
        "settlementId", "viewerRole", "requesterName", "requesterPublicId", "status", "requestedAt",
        "endedAt", "completedAt", "allocationType", "totalAmount", "participantCount",
        "payableParticipantCount", "completedParticipantCount", "storeName", "category",
        "receivableAmount", "amountToPay", "reminderNextAvailableAt", "needsItemSelection"
})
public record GroupSettlementListItemWebResponse(
        UUID settlementId,
        @Schema(description = "조회자 관점. 요청자가 우선이며, 연결된 참여자는 PARTICIPANT, 제3자는 NONE입니다.")
        ViewerRole viewerRole,
        String requesterName,
        String requesterPublicId,
        SettlementStatus status,
        @Schema(description = "요청 시각. 링크 생성 전에는 정산 생성 시각. UTC 기준 offset 없는 값입니다.")
        LocalDateTime requestedAt,
        @Schema(nullable = true, description = "COMPLETED·SUSPENDED의 공통 종료 시각. UTC 기준 offset 없는 값입니다.")
        LocalDateTime endedAt,
        @Schema(nullable = true, description = "COMPLETED의 완료 시각. UTC 기준 offset 없는 값입니다.")
        LocalDateTime completedAt,
        @Schema(nullable = true)
        SettlementAllocationType allocationType,
        @Schema(description = "생성자 몫을 포함한 정산 총액. 제3자에게도 공개합니다.")
        long totalAmount,
        int participantCount,
        @Schema(description = "생성자를 제외한 납부 인원. 확정된 더치페이의 0원 참여자는 제외합니다.")
        int payableParticipantCount,
        int completedParticipantCount,
        @Schema(nullable = true, description = "기존 목록과 같은 단일 영수증 상호명입니다.")
        String storeName,
        GroupSettlementCategoryWebResponse category,
        @Schema(nullable = true, description = "REQUESTER가 받을 금액. 다른 역할은 null입니다.")
        Long receivableAmount,
        @Schema(nullable = true, description = "PARTICIPANT의 개인 납부액. 품목 선택 중·중단 후 미확정이면 총액을 표시합니다. "
                + "확정 더치페이의 0원 참여자는 0, 다른 역할은 null입니다.")
        Long amountToPay,
        @Schema(nullable = true, description = "REQUESTER의 다음 독촉 가능 시각. 기존 상세와 같은 UTC 기준 offset 없는 값이며 "
                + "다른 역할·독촉 불가 상태·대상 또는 활성 쿨다운이 없으면 null입니다. 발송 API가 최종 검증합니다.")
        LocalDateTime reminderNextAvailableAt,
        @Schema(description = "PARTICIPANT가 ITEM_ALLOCATION에서 아직 몫을 안 골라 amountToPay가 총액 표시값이면 true. "
                + "앱은 금액 대신 품목 선택 안내를 그립니다. 다른 역할은 false입니다.")
        boolean needsItemSelection
) {
    public static GroupSettlementListItemWebResponse from(GroupSettlementListItemResult result) {
        var category = result.category();
        return new GroupSettlementListItemWebResponse(
                result.settlementId(), result.viewerRole(), result.requesterName(), result.requesterPublicId(),
                result.status(), result.requestedAt(), result.endedAt(), result.completedAt(),
                result.allocationType(), result.totalAmount(), result.participantCount(),
                result.payableParticipantCount(), result.completedParticipantCount(), result.storeName(),
                new GroupSettlementCategoryWebResponse(category.code(), category.name(), category.icon()),
                result.receivableAmount(), result.amountToPay(), result.reminderNextAvailableAt(),
                result.needsItemSelection());
    }

    @Schema(name = "GroupSettlementCategoryWebResponse", requiredProperties = {"code", "name", "icon"},
            description = "조회자가 지정한 카테고리, 없으면 요청자가 지정한 값, 그것도 없으면 자동 분류 값. "
                    + "없거나 비활성 코드는 UNCATEGORIZED입니다.")
    public record GroupSettlementCategoryWebResponse(String code, String name, @Schema(nullable = true) String icon) {}
}
