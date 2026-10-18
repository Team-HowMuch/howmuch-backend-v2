package com.amateur.howmuchbackend.adapter.in.web.notification.dto.response;

import com.amateur.howmuchbackend.application.port.in.notification.result.NotificationPageResult;
import com.amateur.howmuchbackend.application.port.in.notification.result.NotificationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record NotificationPageWebResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "다음 페이지의 불투명 커서. 마지막 페이지는 null") String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext
) {
    public static NotificationPageWebResponse from(NotificationPageResult result) {
        return new NotificationPageWebResponse(
                result.items().stream().map(Item::from).toList(), result.nextCursor(), result.hasNext()
        );
    }

    @Schema(name = "NotificationItem")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"GROUP_NETTING_CONFIRMED", "GROUP_NETTING_COMPLETED", "NETTED_TRANSFER_REMINDER",
                            "SETTLEMENT_REQUEST", "SETTLEMENT_REMINDER", "SETTLEMENT_PAYMENT_COMPLETED",
                            "SETTLEMENT_COMPLETED", "SETTLEMENT_DEPOSIT_CONFIRMED", "GROUP_INVITATION",
                            "GROUP_MEMBER_LEFT", "MONTHLY_RECAP", "FINANCE_AUTH_REQUIRED"}) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"DUTCH_PAY", "ITEM_ALLOCATION"}, nullable = true) String allocationType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "알림 생성 UTC 시각. offset 없는 값을 UTC로 해석") LocalDateTime createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "최초 읽음 UTC 시각. null이면 안 읽은 알림") LocalDateTime readAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "삭제된 정산은 null") UUID settlementId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Action action
    ) {
        static Item from(NotificationResult result) {
            return new Item(
                    result.id(), result.type().name(), result.allocationType() == null ? null : result.allocationType().name(),
                    result.title(), result.message(), result.createdAt(), result.readAt(), result.settlementId(),
                    new Action(result.action().type(), result.action().label(), result.action().enabled(), result.action().targetId())
            );
        }
    }

    @Schema(name = "NotificationAction")
    public record Action(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"GROUP_SETTLEMENT_NETTING", "SETTLEMENT_PARTICIPATION", "SETTLEMENT_DETAIL", "GROUP_INVITATION",
                            "GROUP_DETAIL", "MONTHLY_RECAP", "FINANCE_AUTHENTICATION"}) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "대상 삭제·권한 소멸·초대 만료 시 false") boolean enabled,
            @Schema(nullable = true, description = "type별 UUID, 양수 groupId 또는 yyyy-MM. 이동 불가하면 null") String targetId
    ) {
        public Action(String type, String label, boolean enabled) { this(type, label, enabled, null); }
    }
}
