package com.amateur.howmuchbackend.adapter.in.web.notification.dto.response;

import com.amateur.howmuchbackend.application.port.in.notification.result.NotificationReadResult;
public record NotificationReadWebResponse(java.util.UUID id, java.time.LocalDateTime readAt) {
    public static NotificationReadWebResponse from(NotificationReadResult result) { return new NotificationReadWebResponse(result.id(), result.readAt()); }
}
