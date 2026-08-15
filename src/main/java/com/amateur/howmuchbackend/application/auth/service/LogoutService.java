package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.in.auth.LogoutUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.LogoutRequest;
import com.amateur.howmuchbackend.application.port.out.auth.AuthAuditPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@Slf4j
@RequiredArgsConstructor
public class LogoutService implements LogoutUseCase {
    private final AuthSessionPort authSessionPort;
    private final CurrentActorPort currentActorPort;
    private final AuthAuditPort authAuditPort;

    @Override
    public void logout(LogoutRequest request) {
        Long userId = currentActorPort.getCurrentActor().userId();
        authSessionPort.deleteByUserId(userId);
        try {
            authAuditPort.recordLogout(
                    userId,
                    LocalDateTime.now(ZoneOffset.UTC),
                    truncate(request.clientIp(), 64),
                    truncate(request.userAgent(), 500)
            );
        } catch (RuntimeException exception) {
            log.warn("Failed to record logout audit. userId={}", userId, exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
