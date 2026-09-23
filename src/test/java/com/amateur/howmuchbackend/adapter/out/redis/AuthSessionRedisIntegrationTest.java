package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.adapter.out.security.jwt.JwtTokenProvider;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.AuthSessionPolicy;
import com.amateur.howmuchbackend.application.auth.service.AuthTokenService;
import com.amateur.howmuchbackend.application.auth.service.LogoutService;
import com.amateur.howmuchbackend.application.port.in.auth.command.LogoutCommand;
import com.amateur.howmuchbackend.application.port.in.auth.command.RefreshTokenCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;
import com.amateur.howmuchbackend.application.port.out.auth.AuthAuditPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.session.AuthSession;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.security.config.JwtProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthSessionRedisIntegrationTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private AuthSessionRedisAdapter sessions;
    private JwtTokenProvider jwt;
    private LoadUserPort users;
    private User user;
    private Long userId;
    private String key;
    private AuthTokenService service;

    @BeforeEach
    void setUp() {
        connection = new LettuceConnectionFactory(
                System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        try (var client = connection.getConnection()) {
            assertThat(client.ping()).isEqualTo("PONG");
        }
        sessions = new AuthSessionRedisAdapter(redis, JsonMapper.builder().findAndAddModules().build());
        jwt = new JwtTokenProvider(new JwtProperties(UUID.randomUUID().toString().repeat(2), 60, 300));
        userId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        key = "auth:session:" + userId;
        user = mock(User.class);
        when(user.id()).thenReturn(userId);
        when(user.active()).thenReturn(true);
        users = mock(LoadUserPort.class);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        service = service(true, true);
    }

    @AfterEach
    void tearDown() {
        if (redis != null && key != null) redis.delete(key);
        if (connection != null) connection.destroy();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void authSession_delayedRefresh_preservesLogoutAndNewLogin(boolean login) throws Exception {
        TokenResult initial = service.issueNewSession(user);
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseAfterValidation(validated, release);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> delayed = executor.submit(() -> refreshOutcome(initial));
            try {
                await(validated);
                if (login) service.issueNewSession(user);
                else logout();
                String stored = redis.opsForValue().get(key);
                Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
                release.countDown();
                assertThat(delayed.get(10, TimeUnit.SECONDS)).isEqualTo(AuthStatusCode.INVALID_TOKEN);
                assertThat(redis.opsForValue().get(key)).isEqualTo(stored);
                if (login) {
                    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
                } else {
                    assertThat(sessions.findByUserId(userId)).isEmpty();
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void authSession_concurrentRefresh_hasSingleWinner() throws Exception {
        TokenResult initial = service.issueNewSession(user);
        CountDownLatch validated = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        pauseAfterValidation(validated, release);
        var outcomes = new ArrayList<Object>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Object>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> refreshOutcome(initial)));
            try {
                await(validated);
                release.countDown();
                for (var future : futures) outcomes.add(future.get(10, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        var winners = outcomes.stream().filter(TokenResult.class::isInstance).map(TokenResult.class::cast).toList();
        assertThat(winners).hasSize(1);
        assertThat(outcomes.stream().filter(AuthStatusCode.INVALID_TOKEN::equals).count()).isEqualTo(7);
        assertThat(sessions.findByUserId(userId).orElseThrow().refreshToken().equals(winners.getFirst().requestToken())).isTrue();
        assertThat(refreshOutcome(initial)).isEqualTo(AuthStatusCode.INVALID_TOKEN);
        assertThat(refreshOutcome(winners.getFirst())).isInstanceOf(TokenResult.class);
    }

    @Test
    void authSession_expiredAfterValidation_notRestored() throws Exception {
        TokenResult initial = service.issueNewSession(user);
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        pauseAfterValidation(validated, release);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> delayed = executor.submit(() -> refreshOutcome(initial));
            try {
                await(validated);
                redis.expire(key, Duration.ofMillis(1));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(5);
                assertThat(redis.hasKey(key)).isFalse();
                release.countDown();
                assertThat(delayed.get(10, TimeUnit.SECONDS)).isEqualTo(AuthStatusCode.INVALID_TOKEN);
                assertThat(redis.hasKey(key)).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void authSession_rotatingRefresh_setsExpiryAndPreservesPolicy(boolean singleSession) {
        service = service(singleSession, true);
        TokenResult initial = service.issueNewSession(user);
        String originalId = jwt.parsePayload(initial.requestToken()).orElseThrow().sessionId();
        TokenResult refreshed = service.refresh(new RefreshTokenCommand(initial.requestToken()));
        var payload = jwt.parsePayload(refreshed.requestToken()).orElseThrow();
        assertThat(payload.sessionId().equals(originalId)).isEqualTo(singleSession);
        assertThat(refreshed.requestToken().equals(initial.requestToken())).isFalse();
        assertThat(sessions.findByUserId(userId).orElseThrow().expiresAt()).isEqualTo(payload.expiresAt());
        long expectedTtl = Duration.between(LocalDateTime.now(ZoneOffset.UTC), payload.expiresAt()).toMillis();
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(expectedTtl - 1000, expectedTtl + 1000);
        logout();
        assertThat(refreshOutcome(refreshed)).isEqualTo(AuthStatusCode.INVALID_TOKEN);
    }

    @Test
    void authSession_nonRotatingPolicy_skipsWriteAndExpiryExtension() {
        service = service(true, false);
        TokenResult initial = service.issueNewSession(user);
        String before = redis.opsForValue().get(key);
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        TokenResult refreshed = service.refresh(new RefreshTokenCommand(initial.requestToken()));
        assertThat(refreshed.requestToken().equals(initial.requestToken())).isTrue();
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
    }

    @Test
    void invalidReplacementDoesNotDeleteCurrentSession() {
        service.issueNewSession(user);
        AuthSession expected = sessions.findByUserId(userId).orElseThrow();
        String before = redis.opsForValue().get(key);
        AuthSession expired = new AuthSession(userId, "next", "synthetic", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        assertThat(sessions.rotate(expected, expired)).isFalse();
        assertThatThrownBy(() -> sessions.rotate(expected,
                new AuthSession(userId + 1, "next", "synthetic", expected.expiresAt())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "token", "expiry", "ttl", "json"})
    void changedOrInvalidStoredStateIsPreserved(String change) {
        service.issueNewSession(user);
        AuthSession expected = sessions.findByUserId(userId).orElseThrow();
        switch (change) {
            case "session" -> sessions.save(new AuthSession(userId, "other", expected.refreshToken(), expected.expiresAt()));
            case "token" -> sessions.save(new AuthSession(userId, expected.sessionId(), "other", expected.expiresAt()));
            case "expiry" -> sessions.save(new AuthSession(userId, expected.sessionId(), expected.refreshToken(), expected.expiresAt().plusSeconds(30)));
            case "ttl" -> redis.persist(key);
            case "json" -> redis.opsForValue().set(key, "invalid-json", Duration.ofMinutes(1));
        }
        String before = redis.opsForValue().get(key);
        assertThat(sessions.rotate(expected, new AuthSession(userId, "next", "synthetic", expected.expiresAt()))).isFalse();
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
    }

    private AuthTokenService service(boolean singleSession, boolean rotate) {
        return new AuthTokenService(jwt, sessions, users, new AuthSessionPolicy(singleSession, rotate));
    }

    private Object refreshOutcome(TokenResult token) {
        try {
            return service.refresh(new RefreshTokenCommand(token.requestToken()));
        } catch (ApplicationException exception) {
            return exception.getStatusCode();
        }
    }

    private void pauseAfterValidation(CountDownLatch validated, CountDownLatch release) {
        when(users.findById(userId)).thenAnswer(call -> {
            validated.countDown();
            await(release);
            return Optional.of(user);
        });
    }

    private void logout() {
        var actor = mock(CurrentActorPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(userId));
        new LogoutService(sessions, actor, mock(AuthAuditPort.class)).logout(new LogoutCommand(null, null));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
