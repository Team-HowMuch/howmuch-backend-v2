package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.domain.auth.session.AuthSession;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthSessionPort {
    Optional<AuthSession> findByUserId(Long userId);

    boolean existsActiveByUserIdAndSessionId(Long userId, String sessionId, LocalDateTime now);

    void save(AuthSession authSession);

    void deleteByUserId(Long userId);
}
