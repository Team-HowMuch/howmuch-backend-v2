package com.amateur.howmuchbackend.adapter.in.web.notification.dto.response;

import com.amateur.howmuchbackend.application.port.in.notification.result.NotificationReadResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationReadWebResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "최초 읽음 UTC 시각") LocalDateTime readAt
) {
    public static NotificationReadWebResponse from(NotificationReadResult result) {
        return new NotificationReadWebResponse(result.id(), result.readAt());
    }
}
