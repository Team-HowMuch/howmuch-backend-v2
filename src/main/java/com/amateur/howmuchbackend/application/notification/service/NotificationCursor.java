package com.amateur.howmuchbackend.application.notification.service;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.notification.status.NotificationStatusCode;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

record NotificationCursor(LocalDateTime createdAt, UUID id) {
    static NotificationCursor decode(String value) {
        if (value == null) return null;
        try {
            if (value.isBlank() || value.length() > 256) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new NotificationCursor(LocalDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw ApplicationException.of(NotificationStatusCode.INVALID_NOTIFICATION_CURSOR);
        }
    }
    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
}
