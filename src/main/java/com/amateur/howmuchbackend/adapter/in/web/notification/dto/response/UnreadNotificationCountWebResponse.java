package com.amateur.howmuchbackend.adapter.in.web.notification.dto.response;

import com.amateur.howmuchbackend.application.port.in.notification.result.UnreadNotificationCountResult;
public record UnreadNotificationCountWebResponse(long count) {
    public static UnreadNotificationCountWebResponse from(UnreadNotificationCountResult result) { return new UnreadNotificationCountWebResponse(result.count()); }
}
