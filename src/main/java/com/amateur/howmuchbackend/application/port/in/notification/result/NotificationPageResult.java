package com.amateur.howmuchbackend.application.port.in.notification.result;

public record NotificationPageResult(java.util.List<NotificationResult> items, String nextCursor, boolean hasNext) {}
