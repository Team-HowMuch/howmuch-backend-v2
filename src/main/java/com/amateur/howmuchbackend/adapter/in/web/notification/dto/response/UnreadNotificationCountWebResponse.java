package com.amateur.howmuchbackend.adapter.in.web.notification.dto.response;

import com.amateur.howmuchbackend.application.port.in.notification.result.UnreadNotificationCountResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record UnreadNotificationCountWebResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "내 미읽음 알림 수") long count
) {
    public static UnreadNotificationCountWebResponse from(UnreadNotificationCountResult result) {
        return new UnreadNotificationCountWebResponse(result.count());
    }
}
