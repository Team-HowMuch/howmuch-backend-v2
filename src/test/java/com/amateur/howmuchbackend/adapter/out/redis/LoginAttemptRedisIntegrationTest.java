package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.auth.service.AuthTokenService;
import com.amateur.howmuchbackend.application.auth.service.LoginService;
import com.amateur.howmuchbackend.application.port.in.auth.command.LoginCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.RecordUserLoginPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginAttemptRedisIntegrationTest {
    private static final String PHONE = "01000000000";
    private static final String PIN = "246802";
    private static final Duration LOCK = Duration.ofSeconds(30);
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private LoginAttemptRedisAdapter attempts;
    private String phoneHash;
    private String key;
    private LoadUserPort users;
    private RecordUserLoginPort loginRecords;
    private PasswordEncoder encoder;
    private AuthTokenService tokens;
    private User user;
    private LoginService service;

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
        attempts = new LoginAttemptRedisAdapter(redis);
        var identity = new IdentityPolicy(UUID.randomUUID().toString());
        phoneHash = identity.phoneHash(PHONE);
        key = "auth:login:attempt:" + phoneHash;
        users = mock(LoadUserPort.class);
        loginRecords = mock(RecordUserLoginPort.class);
        encoder = mock(PasswordEncoder.class);
        tokens = mock(AuthTokenService.class);
        user = mock(User.class);
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(user));
        when(user.pinHash()).thenReturn("synthetic-hash");
        when(user.active()).thenReturn(true);
        when(user.markLoggedIn(any())).thenReturn(user);
        when(tokens.issueNewSession(user)).thenReturn(new TokenResult("synthetic-access", "synthetic-request"));
        service = new LoginService(new PinPolicy(5, LOCK), identity, attempts, users, loginRecords, encoder, tokens);
    }

    @AfterEach
    void tearDown() {
        if (redis != null && key != null) redis.delete(key);
        if (connection != null) connection.destroy();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4})
    void loginAttempt_concurrentRequests_cannotExceedRemaining(int failures) throws Exception {
        if (failures > 0) redis.opsForValue().set(key, String.valueOf(failures), LOCK);
        int allowed = 5 - failures;
        var entered = new CountDownLatch(allowed);
        var release = new CountDownLatch(1);
        var rejected = new CountDownLatch(8 - allowed);
        when(encoder.matches(PIN, "synthetic-hash")).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return false;
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Object>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    Object result = login(PIN);
                    rejected.countDown();
                    return result;
                }));
            }
            try {
                start.countDown();
                await(entered);
                // 허용된 PIN 검증이 모두 대기 중이어도 초과 요청은 그보다 먼저 끝나야 한다.
                await(rejected);
                verify(encoder, times(allowed)).matches(PIN, "synthetic-hash");
                release.countDown();
                var results = new ArrayList<Object>();
                for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
                assertThat(results.stream().filter(AuthStatusCode.INVALID_LOGIN_CREDENTIALS::equals).count())
                        .isEqualTo(allowed - 1);
                assertThat(results.stream().filter(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED::equals).count())
                        .isEqualTo(9 - allowed);
                assertThat(login(PIN)).isEqualTo(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED);
                verify(encoder, times(allowed)).matches(PIN, "synthetic-hash");
                verifyNoInteractions(loginRecords, tokens);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void loginAttempt_successOnLastTry_resetsAndAllowsRetry() {
        redis.opsForValue().set(key, "4", LOCK);
        when(encoder.matches(PIN, "synthetic-hash")).thenReturn(true);
        assertThat(login(PIN)).isInstanceOf(TokenResult.class);
        assertThat(redis.hasKey(key)).isFalse();
        verify(loginRecords).recordLogin(user.id(), user.lastLoginAt());
        verify(tokens).issueNewSession(user);
        when(encoder.matches(PIN, "synthetic-hash")).thenReturn(false);
        assertThat(login(PIN)).isEqualTo(AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
        assertThat(redis.opsForValue().get(key)).endsWith(":1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void loginAttempt_delayedCompletion_cannotChangeNewGeneration(boolean success) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(encoder.matches(PIN, "synthetic-hash")).thenAnswer(call -> {
            entered.countDown();
            await(release);
            return success;
        });
        when(encoder.matches("246803", "synthetic-hash")).thenReturn(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> delayed = executor.submit(() -> login(PIN));
            try {
                await(entered);
                assertThat(login("246803")).isInstanceOf(TokenResult.class);
                assertThat(redis.hasKey(key)).isFalse();
                assertThat(login("246804")).isEqualTo(AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
                String state = redis.opsForValue().get(key);
                long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
                release.countDown();
                Object outcome = delayed.get(10, TimeUnit.SECONDS);
                if (success) assertThat(outcome).isInstanceOf(TokenResult.class);
                else assertThat(outcome).isEqualTo(AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
                assertThat(redis.opsForValue().get(key)).isEqualTo(state);
                assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void loginAttempt_expiryAllowsRetry_oldSuccessCannotResetNewLock() throws Exception {
        var old = attempts.reserve(phoneHash, 1, Duration.ofMillis(100)).orElseThrow();
        assertThat(attempts.reserve(phoneHash, 1, LOCK)).isEmpty();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(redis.hasKey(key)).isFalse();
        var next = attempts.reserve(phoneHash, 1, LOCK).orElseThrow();
        assertThat(next.generation()).isNotEqualTo(old.generation());
        String state = redis.opsForValue().get(key);
        attempts.resetAfterSuccess(phoneHash, old.generation());
        assertThat(redis.opsForValue().get(key)).isEqualTo(state);
        assertThat(attempts.reserve(phoneHash, 1, LOCK)).isEmpty();
    }

    @Test
    void loginAttempt_legacyCount_preservesTtlWithoutExtendingLock() {
        redis.opsForValue().set(key, "2", Duration.ofSeconds(10));
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(attempts.reserve(phoneHash, 5, LOCK).orElseThrow().attemptCount()).isEqualTo(3);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
        assertThat(attempts.reserve(phoneHash, 5, LOCK).orElseThrow().attemptCount()).isEqualTo(4);
        assertThat(attempts.reserve(phoneHash, 5, LOCK).orElseThrow().attemptCount()).isEqualTo(5);
        ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(25000L, 30000L);
        assertThat(login(PIN)).isEqualTo(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
        verifyNoInteractions(users, encoder, tokens);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5", "6", "invalid", "generation:invalid"})
    void loginAttempt_legacyState_lockedOrMalformed_preserved(String state) {
        redis.opsForValue().set(key, state, LOCK);
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(login(PIN)).isEqualTo(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED);
        assertThat(redis.opsForValue().get(key)).isEqualTo(state);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
        verifyNoInteractions(users, encoder);
    }

    @Test
    void loginAttempt_noExpiry_remainsBlocked() {
        redis.opsForValue().set(key, "1");
        assertThat(login(PIN)).isEqualTo(AuthStatusCode.PIN_ATTEMPTS_EXCEEDED);
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        verifyNoInteractions(users, encoder);
    }

    @Test
    void invalidFormatDoesNotReserve() {
        assertThat(login("bad")).isEqualTo(AuthStatusCode.INVALID_PIN_FORMAT);
        assertThat(redis.hasKey(key)).isFalse();
        verifyNoInteractions(users, encoder);
    }

    @Test
    void missingUserConsumesAttemptWithoutPinVerification() {
        when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.empty());
        assertThat(login(PIN)).isEqualTo(AuthStatusCode.INVALID_LOGIN_CREDENTIALS);
        assertThat(redis.opsForValue().get(key)).endsWith(":1");
        verifyNoInteractions(encoder, loginRecords, tokens);
    }

    @Test
    void inactiveUserDoesNotResetAttemptsOrIssueTokens() {
        when(encoder.matches(PIN, "synthetic-hash")).thenReturn(true);
        when(user.active()).thenReturn(false);
        assertThat(login(PIN)).isEqualTo(AuthStatusCode.USER_INACTIVE);
        assertThat(redis.opsForValue().get(key)).endsWith(":1");
        verifyNoInteractions(loginRecords, tokens);
    }

    @Test
    void verificationExceptionDoesNotRefundAttempt() {
        when(encoder.matches(PIN, "synthetic-hash")).thenThrow(new IllegalStateException("verification unavailable"));
        assertThatThrownBy(() -> login(PIN)).isInstanceOf(IllegalStateException.class);
        assertThat(redis.opsForValue().get(key)).endsWith(":1");
        verifyNoInteractions(loginRecords, tokens);
    }

    private Object login(String pin) {
        try {
            return service.login(new LoginCommand(PHONE, pin));
        } catch (ApplicationException exception) {
            return exception.getStatusCode();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
