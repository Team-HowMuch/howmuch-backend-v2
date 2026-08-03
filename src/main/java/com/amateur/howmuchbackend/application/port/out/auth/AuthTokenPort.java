package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.application.port.out.auth.jwt.JwtPayload;
import com.amateur.howmuchbackend.domain.user.User;

import java.util.Optional;

public interface AuthTokenPort {
    String createAccessToken(User user, String sessionId);

    String createRefreshToken(User user, String sessionId);

    Optional<JwtPayload> parsePayload(String token);
}
