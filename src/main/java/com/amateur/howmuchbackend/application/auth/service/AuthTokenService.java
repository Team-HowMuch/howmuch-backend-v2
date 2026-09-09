package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.AuthSessionPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.RefreshTokenUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.command.RefreshTokenCommand;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.JwtPayload;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.enums.JwtTokenType;
import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
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
    private final LoadUserPort loadUserPort;
    private final AuthSessionPolicy authSessionPolicy;

    public TokenResult issueNewSession(User user) {
        return issue(user, UUID.randomUUID().toString(), null);
    }

    @Override
    public TokenResult refresh(RefreshTokenCommand command) {
        String requestToken = command.requestToken();
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

        User user = loadUserPort.findById(session.userId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.INVALID_TOKEN));

        String sessionId = authSessionPolicy.singleSessionPerUser()
                ? session.sessionId()
                : UUID.randomUUID().toString();
        if (!authSessionPolicy.rotateRequestToken()) {
            String accessToken = authTokenPort.createAccessToken(user, sessionId);
            return new TokenResult(accessToken, requestToken);
        }
        return issue(user, sessionId, session);
    }

    private TokenResult issue(User user, String sessionId, AuthSession expected) {
        String accessToken = authTokenPort.createAccessToken(user, sessionId);
        String requestToken = authTokenPort.createRefreshToken(user, sessionId);
        JwtPayload requestPayload = authTokenPort.parsePayload(requestToken)
                .filter(value -> value.isType(JwtTokenType.REFRESH))
                .orElseThrow(() -> new IllegalStateException("Issued request token is invalid."));

        AuthSession replacement = new AuthSession(user.id(), sessionId, requestToken, requestPayload.expiresAt());
        if (expected == null) {
            authSessionPort.save(replacement);
        } else if (!authSessionPort.rotate(expected, replacement)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_TOKEN);
        }
        return new TokenResult(accessToken, requestToken);
    }
}
