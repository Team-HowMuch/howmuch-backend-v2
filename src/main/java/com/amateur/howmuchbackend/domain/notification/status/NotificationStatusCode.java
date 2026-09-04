package com.amateur.howmuchbackend.domain.notification.status;

import com.amateur.howmuchbackend.common.exception.StatusCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NotificationStatusCode implements StatusCode {
    NOTIFICATION_NOT_FOUND("알림을 찾을 수 없습니다.", 404),
    INVALID_NOTIFICATION_CURSOR("알림 커서가 올바르지 않습니다.", 400),
    INVALID_NOTIFICATION_PAGE_SIZE("알림 조회 크기는 1~50이어야 합니다.", 400);
    private final String message;
    private final int status;
    @Override public String getCode() { return name(); }
}
