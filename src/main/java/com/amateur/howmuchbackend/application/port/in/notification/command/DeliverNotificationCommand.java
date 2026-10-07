package com.amateur.howmuchbackend.application.port.in.notification.command;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record DeliverNotificationCommand(UUID messageId, UUID deliveryId, long generation,
        Long recipientUserId, LocalDateTime occurredAt) {
    public DeliverNotificationCommand {
        Objects.requireNonNull(messageId); Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(recipientUserId); Objects.requireNonNull(occurredAt);
        if (generation < 0 || recipientUserId < 1) throw new IllegalArgumentException("Invalid notification delivery identity.");
    }
}
