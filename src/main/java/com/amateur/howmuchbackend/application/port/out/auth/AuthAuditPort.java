package com.amateur.howmuchbackend.application.port.out.auth;

import java.time.LocalDateTime;

public interface AuthAuditPort {
    void recordLogout(Long userId, LocalDateTime occurredAt, String clientIp, String userAgent);

    void recordAdminUserDeletion(Long adminUserId, Long targetUserId, LocalDateTime occurredAt);
}
