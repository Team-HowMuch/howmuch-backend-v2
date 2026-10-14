package com.amateur.howmuchbackend.application.port.out.auth;

import java.time.LocalDateTime;

public interface WidgetTokenPort {
    String createWidgetToken(Long userId, String sessionId, LocalDateTime expiresAt);
}
