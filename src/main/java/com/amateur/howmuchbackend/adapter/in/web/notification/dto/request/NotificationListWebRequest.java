package com.amateur.howmuchbackend.adapter.in.web.notification.dto.request;

import com.amateur.howmuchbackend.application.port.in.notification.command.GetMyNotificationsCommand;
import jakarta.validation.constraints.*;

public record NotificationListWebRequest(@Size(max=256) String cursor, @Min(1) @Max(50) Integer size) {
    public GetMyNotificationsCommand toCommand() { return new GetMyNotificationsCommand(cursor, size); }
}
