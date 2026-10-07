package com.amateur.howmuchbackend.application.port.in.notification;

import com.amateur.howmuchbackend.application.port.in.notification.command.DeliverNotificationCommand;

import java.util.UUID;

public interface DeliverNotificationUseCase {
    void deliver(UUID deliveryId, long generation);
    default void deliver(DeliverNotificationCommand command) { deliver(command.deliveryId(), command.generation()); }
}
