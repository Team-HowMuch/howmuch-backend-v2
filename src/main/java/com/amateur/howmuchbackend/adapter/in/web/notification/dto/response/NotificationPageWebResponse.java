package com.amateur.howmuchbackend.adapter.in.web.notification.dto.response;

import com.amateur.howmuchbackend.application.port.in.notification.result.*;
import java.time.LocalDateTime;
import java.util.*;

public record NotificationPageWebResponse(List<Item> items, String nextCursor, boolean hasNext) {
    public static NotificationPageWebResponse from(NotificationPageResult result) {
        return new NotificationPageWebResponse(result.items().stream().map(Item::from).toList(), result.nextCursor(), result.hasNext());
    }
    public record Item(UUID id, String type, String allocationType, String title, String message,
            LocalDateTime createdAt, LocalDateTime readAt, UUID settlementId, Action action) {
        static Item from(NotificationResult n) {
            return new Item(n.id(), n.type().name(), n.allocationType().name(), n.title(), n.message(), n.createdAt(),
                    n.readAt(), n.settlementId(), new Action(n.action().type(), n.action().label(), n.action().enabled()));
        }
    }
    public record Action(String type, String label, boolean enabled) {}
}
