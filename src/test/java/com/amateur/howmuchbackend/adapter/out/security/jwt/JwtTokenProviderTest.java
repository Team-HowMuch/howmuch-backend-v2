package com.amateur.howmuchbackend.adapter.out.security.jwt;

import com.amateur.howmuchbackend.application.auth.policy.AuthSessionPolicy;
import com.amateur.howmuchbackend.application.auth.service.AuthTokenService;
import com.amateur.howmuchbackend.application.port.in.auth.command.RefreshTokenCommand;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.enums.JwtTokenType;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.security.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtTokenProviderTest {
    private final String testKey = UUID.randomUUID().toString().repeat(2);
    private final JwtTokenProvider provider = new JwtTokenProvider(new JwtProperties(testKey, 1800, 86400));
    private final User user = mock(User.class);

    @ParameterizedTest
    @EnumSource(JwtTokenType.class)
    void jwt_sameSecondIssuance_uniqueJtiAndClaims(JwtTokenType type) {
        when(user.id()).thenReturn(7L);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        try (var time = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            time.when(() -> LocalDateTime.now(ZoneOffset.UTC)).thenReturn(now);
            String first = issue(type);
            String second = issue(type);
            assertThat(first.equals(second)).isFalse();
            Claims a = claims(first);
            Claims b = claims(second);
            assertThat(a.getId().equals(b.getId())).isFalse();
            assertThatCode(() -> UUID.fromString(a.getId())).doesNotThrowAnyException();
            assertThatCode(() -> UUID.fromString(b.getId())).doesNotThrowAnyException();
            assertThat(a.getSubject()).isEqualTo("7");
            assertThat(a.get("tokenType", String.class)).isEqualTo(type.name());
            assertThat(a.get("sessionId", String.class)).isEqualTo("session");
            assertThat(a.getIssuedAt()).isEqualTo(Date.from(now.toInstant(ZoneOffset.UTC)));
            assertThat(a.getExpiration()).isEqualTo(Date.from(now.plusSeconds(
                    type == JwtTokenType.ACCESS ? 1800 : 86400).toInstant(ZoneOffset.UTC)));
            assertThat(provider.parsePayload(first)).isEqualTo(provider.parsePayload(second));
        }
    }

    @ParameterizedTest
    @EnumSource(JwtTokenType.class)
    void jwt_legacyWithoutJti_accepted(JwtTokenType type) {
        String legacy = Jwts.builder().subject("7")
                .claim("sessionId", "session").claim("tokenType", type.name())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(testKey.getBytes(StandardCharsets.UTF_8))).compact();
        assertThat(claims(legacy).getId()).isNull();
        var payload = provider.parsePayload(legacy).orElseThrow();
        assertThat(payload.userId()).isEqualTo(7L);
        assertThat(payload.sessionId()).isEqualTo("session");
        assertThat(payload.isType(type)).isTrue();
    }

    @Test
    void jwt_previousRefreshToken_rejectedWithinSameSecond() {
        when(user.id()).thenReturn(7L);
        when(user.active()).thenReturn(true);
        var current = new AtomicReference<AuthSession>();
        var sessions = mock(AuthSessionPort.class);
        when(sessions.findByUserId(7L)).thenAnswer(call -> Optional.ofNullable(current.get()));
        doAnswer(call -> { current.set(call.getArgument(0)); return null; }).when(sessions).save(any());
        when(sessions.rotate(any(), any())).thenAnswer(call ->
                current.compareAndSet(call.getArgument(0), call.getArgument(1)));
        var users = mock(LoadUserPort.class);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        var service = new AuthTokenService(provider, sessions, users, new AuthSessionPolicy(true, true));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        try (var time = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            time.when(() -> LocalDateTime.now(ZoneOffset.UTC)).thenReturn(now);
            var original = service.issueNewSession(user);
            var rotated = service.refresh(new RefreshTokenCommand(original.requestToken()));
            assertThat(original.requestToken().equals(rotated.requestToken())).isFalse();
            assertThatThrownBy(() -> service.refresh(new RefreshTokenCommand(original.requestToken())))
                    .isInstanceOf(ApplicationException.class);
            assertThatCode(() -> service.refresh(new RefreshTokenCommand(rotated.requestToken())))
                    .doesNotThrowAnyException();
        }
    }

    private String issue(JwtTokenType type) {
        return type == JwtTokenType.ACCESS
                ? provider.createAccessToken(user, "session") : provider.createRefreshToken(user, "session");
    }

    private Claims claims(String value) {
        return Jwts.parser().verifyWith(Keys.hmacShaKeyFor(testKey.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims(value).getPayload();
    }
}
