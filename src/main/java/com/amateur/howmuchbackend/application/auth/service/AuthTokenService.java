package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.AuthSessionPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.RefreshTokenUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.request.RefreshTokenRequest;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.JwtPayload;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.enums.JwtTokenType;
import com.amateur.howmuchbackend.application.port.in.auth.response.TokenResponse;
import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthTokenService implements RefreshTokenUseCase {
    private final AuthTokenPort authTokenPort;
    private final AuthSessionPort authSessionPort;
    private final UserRepositoryPort userRepositoryPort;
    private final AuthSessionPolicy authSessionPolicy;

    public TokenResponse issueNewSession(User user) {
        return issue(user, UUID.randomUUID().toString());
    }

    @Override
    public TokenResponse refresh(RefreshTokenRequest request) {
        String requestToken = request.requestToken();
        if (!StringUtils.hasText(requestToken)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_TOKEN);
        }

        JwtPayload payload = authTokenPort.parsePayload(requestToken)
                .filter(value -> value.isType(JwtTokenType.REFRESH))
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.INVALID_TOKEN));

        AuthSession session = authSessionPort.findByUserId(payload.userId())
                .filter(value -> value.matches(payload.sessionId(), requestToken))
                .filter(value -> !value.isExpired(LocalDateTime.now(ZoneOffset.UTC)))
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.INVALID_TOKEN));

        User user = userRepositoryPort.findById(session.userId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.INVALID_TOKEN));

        String sessionId = authSessionPolicy.singleSessionPerUser()
                ? session.sessionId()
                : UUID.randomUUID().toString();
        if (!authSessionPolicy.rotateRequestToken()) {
            String accessToken = authTokenPort.createAccessToken(user, sessionId);
            return new TokenResponse(accessToken, requestToken);
        }
        return issue(user, sessionId);
    }

    private TokenResponse issue(User user, String sessionId) {
        String accessToken = authTokenPort.createAccessToken(user, sessionId);
        String requestToken = authTokenPort.createRefreshToken(user, sessionId);
        JwtPayload requestPayload = authTokenPort.parsePayload(requestToken)
                .filter(value -> value.isType(JwtTokenType.REFRESH))
                .orElseThrow(() -> new IllegalStateException("Issued request token is invalid."));

        authSessionPort.save(new AuthSession(user.id(), sessionId, requestToken, requestPayload.expiresAt()));
        return new TokenResponse(accessToken, requestToken);
    }
}
