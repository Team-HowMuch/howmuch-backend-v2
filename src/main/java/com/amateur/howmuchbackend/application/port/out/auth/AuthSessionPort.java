package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.domain.auth.session.AuthSession;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthSessionPort {
    Optional<AuthSession> findByUserId(Long userId);

    boolean existsActiveByUserIdAndSessionId(Long userId, String sessionId, LocalDateTime now);

    /** Creates or replaces a session for a new login. */
    void save(AuthSession authSession);

    /** Replaces only the still-active expected session; a conflict leaves storage unchanged. */
    boolean rotate(AuthSession expected, AuthSession replacement);

    void deleteByUserId(Long userId);
}
