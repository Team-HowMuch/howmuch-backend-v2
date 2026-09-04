package com.amateur.howmuchbackend.adapter.in.web.notification.dto.request;

import com.amateur.howmuchbackend.application.port.in.notification.command.GetMyNotificationsCommand;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationListWebRequest(
        @Size(max = 256) @Schema(description = "이전 응답의 nextCursor") String cursor,
        @Min(1) @Max(50) @Schema(description = "페이지 크기", defaultValue = "20") Integer size
) {
    public GetMyNotificationsCommand toCommand() { return new GetMyNotificationsCommand(cursor, size); }
}
