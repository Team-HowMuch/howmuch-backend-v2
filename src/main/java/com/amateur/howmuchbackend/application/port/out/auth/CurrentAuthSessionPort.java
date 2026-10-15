package com.amateur.howmuchbackend.application.port.out.auth;

public interface CurrentAuthSessionPort {
    AuthenticatedSession getCurrentSession();

    record AuthenticatedSession(Long userId, String sessionId) {}
}
