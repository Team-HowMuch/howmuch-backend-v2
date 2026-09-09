package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.auth.service.ConfirmSmsVerificationService;
import com.amateur.howmuchbackend.application.auth.service.SendSmsVerificationService;
import com.amateur.howmuchbackend.application.port.in.auth.command.ConfirmSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.in.auth.command.SendSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationConfirmedResult;
import com.amateur.howmuchbackend.application.port.out.auth.SmsSenderPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SmsVerificationRedisIntegrationTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final IdentityPolicy identity = new IdentityPolicy(UUID.randomUUID().toString());
    private final SmsVerificationPolicy policy = new SmsVerificationPolicy(
            Duration.ofMinutes(3), Duration.ofMinutes(30), Duration.ofSeconds(60), 5);
    private final List<String> challengeKeys = new ArrayList<>();
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private SmsVerificationRedisAdapter adapter;
    private String phoneHash;
    private String key;
    private SmsOtpState initial;

    @BeforeEach
    void setUp() throws Exception {
        connection = new LettuceConnectionFactory(
                System.getenv().getOrDefault("REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        try (var client = connection.getConnection()) {
            assertThat(client.ping()).isEqualTo("PONG");
        }
        adapter = new SmsVerificationRedisAdapter(redis, mapper);
        phoneHash = identity.phoneHash("01012345678");
        key = "auth:sms:otp:" + phoneHash;
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((phoneHash + ":123456").getBytes(StandardCharsets.UTF_8)));
        initial = new SmsOtpState(UUID.randomUUID().toString(), "01012345678", hash, 0,
                now().plusSeconds(60), now().plusMinutes(3));
        saveState(initial, Duration.ofMinutes(3));
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            redis.delete(key);
            redis.delete("auth:sms:cooldown:" + phoneHash);
            challengeKeys.forEach(redis::delete);
        }
        if (connection != null) connection.destroy();
    }

    @Test
    void concurrentFailuresFromSameSnapshotAreBothCountedAndKeepTtl() throws Exception {
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        SmsVerificationRedisAdapter racing = synchronizedReads(2);
        assertThat(race(List.of(() -> fail(racing), () -> fail(racing))))
                .containsExactly(CODE_MISMATCH, CODE_MISMATCH);
        assertThat(storedState().orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
    }

    @Test
    void concurrentFailuresStopAtLimitAndRejectCorrectCode() throws Exception {
        SmsVerificationRedisAdapter racing = synchronizedReads(8);
        List<Callable<SmsOtpState.Outcome>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> fail(racing));
        List<SmsOtpState.Outcome> outcomes = race(calls);
        assertThat(outcomes.stream().filter(CODE_MISMATCH::equals).count()).isEqualTo(4);
        assertThat(outcomes.stream().filter(ATTEMPTS_EXCEEDED::equals).count()).isEqualTo(4);
        assertThat(storedState().orElseThrow().attemptCount()).isEqualTo(5);
        assertThat(succeed(adapter)).isEqualTo(ATTEMPTS_EXCEEDED);
        assertCooldown();
    }

    @Test
    void successRacingFailureCannotRestoreConsumedOtp() throws Exception {
        SmsVerificationRedisAdapter racing = synchronizedReads(2);
        List<SmsOtpState.Outcome> results = race(List.of(() -> succeed(racing), () -> fail(racing)));
        assertThat(results.getFirst()).isEqualTo(VERIFIED);
        assertThat(results.get(1)).isIn(CODE_MISMATCH, EXPIRED);
        assertThat(storedState().orElseThrow().consumed()).isTrue();
        assertThat(fail(adapter)).isEqualTo(EXPIRED);
        assertThat(succeed(adapter)).isEqualTo(EXPIRED);
        assertCooldown();
    }

    @Test
    void concurrentConfirmationsIssueExactlyOneStoredChallenge() throws Exception {
        AuthChallengeRedisAdapter challenges = spy(new AuthChallengeRedisAdapter(redis, mapper));
        ConfirmSmsVerificationService service = new ConfirmSmsVerificationService(
                identity, policy, synchronizedReads(2), challenges);
        Callable<Object> confirm = () -> {
            try {
                return service.confirm(new ConfirmSmsVerificationCommand(
                        initial.phoneNumber(), initial.verificationRequestId(), "123456"));
            } catch (ApplicationException exception) {
                return exception.getStatusCode();
            }
        };
        List<Object> results = race(List.of(confirm, confirm));
        List<SmsVerificationConfirmedResult> successes = results.stream()
                .filter(SmsVerificationConfirmedResult.class::isInstance)
                .map(SmsVerificationConfirmedResult.class::cast).toList();
        successes.forEach(result -> challengeKeys.add("auth:challenge:" + result.challengeId()));
        assertThat(successes).hasSize(1);
        assertThat(results).contains(AuthStatusCode.SMS_VERIFICATION_EXPIRED);
        assertThat(challenges.findById(successes.getFirst().challengeId())).isPresent();
        verify(challenges, times(1)).save(any(), any());
    }

    @Test
    void staleFailureCannotOverwriteReplacementRequest() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SmsVerificationRedisAdapter delayed = delayedRead(read, release);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> fail(delayed));
            try {
                assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
                SmsOtpState replacement = new SmsOtpState("replacement", initial.phoneNumber(),
                        initial.codeHash(), 0, now().plusSeconds(60), now().plusMinutes(3));
                saveState(replacement, Duration.ofMinutes(3));
                release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(CODE_MISMATCH);
                assertThat(storedState()).contains(replacement);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void delayedFailureCannotRestoreAlreadyConsumedOtp() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SmsVerificationRedisAdapter delayed = delayedRead(read, release);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> fail(delayed));
            try {
                assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(succeed(adapter)).isEqualTo(VERIFIED);
                release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(EXPIRED);
                SmsOtpState stored = storedState().orElseThrow();
                assertThat(stored.consumed()).isTrue();
                assertThat(stored.attemptCount()).isZero();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void redisExpiryBetweenReadAndTransitionDoesNotRecreateKey() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SmsVerificationRedisAdapter delayed = delayedRead(read, release);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> fail(delayed));
            try {
                assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
                redis.expire(key, Duration.ofMillis(1));
                TimeUnit.MILLISECONDS.sleep(20);
                assertThat(redis.hasKey(key)).isFalse();
                release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(EXPIRED);
                assertThat(redis.hasKey(key)).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void consumedStateAllowsResendAfterOriginalCooldown() {
        saveState(new SmsOtpState(initial.verificationRequestId(), initial.phoneNumber(),
                initial.codeHash(), 0, now().minusSeconds(1), now().plusMinutes(3)), Duration.ofMinutes(3));
        assertThat(succeed(adapter)).isEqualTo(VERIFIED);
        SmsSenderPort sender = mock(SmsSenderPort.class);
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, adapter, sender);
        var sent = service.send(new SendSmsVerificationCommand(initial.phoneNumber()));
        assertThat(sent.verificationRequestId()).isNotEqualTo(initial.verificationRequestId());
        SmsOtpState replacement = storedState().orElseThrow();
        assertThat(replacement.verificationRequestId()).isEqualTo(sent.verificationRequestId());
        assertThat(replacement.consumed()).isFalse();
        assertThat(replacement.attemptCount()).isZero();
        verify(sender).send(eq(initial.phoneNumber()), anyString());
        assertThat(succeed(adapter)).isEqualTo(CODE_MISMATCH);
    }

    @Test
    void ttlExpiryBetweenReadAndTransitionRejectsCorrectCodeDespiteFutureTimestamp() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        saveState(new SmsOtpState(initial.verificationRequestId(), initial.phoneNumber(),
                initial.codeHash(), 0, now(), now().plusHours(1)), Duration.ofMinutes(3));
        SmsVerificationRedisAdapter delayed = delayedRead(read, release);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> succeed(delayed));
            try {
                assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
                redis.expire(key, Duration.ofMillis(1));
                TimeUnit.MILLISECONDS.sleep(20);
                assertThat(redis.hasKey(key)).isFalse();
                release.countDown();
                assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(EXPIRED);
                assertThat(redis.hasKey(key)).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-3600, 3600})
    void validTtlIsIndependentOfApplicationClockOffset(long offsetSeconds) {
        saveState(new SmsOtpState(initial.verificationRequestId(), initial.phoneNumber(),
                initial.codeHash(), 0, now().plusSeconds(offsetSeconds), now().plusSeconds(offsetSeconds)),
                Duration.ofMinutes(3));
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(succeed(adapter)).isEqualTo(VERIFIED);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
        assertThat(succeed(adapter)).isEqualTo(EXPIRED);
    }

    @Test
    void keyWithoutExpiryCannotBeConsumed() {
        redis.persist(key);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isEqualTo(-1);
        assertThat(succeed(adapter)).isEqualTo(EXPIRED);
        assertThat(storedState()).contains(initial);
    }

    @Test
    void missingKeyAndWrongRequestDoNotIssueSuccess() {
        assertThat(adapter.verifyAndConsume(phoneHash, "old-request", initial.codeHash(), 5))
                .isEqualTo(CODE_MISMATCH);
        assertThat(storedState()).contains(initial);
        redis.delete(key);
        assertThat(succeed(adapter)).isEqualTo(EXPIRED);
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void legacyJsonWithoutConsumedCanBeVerifiedOnce() {
        String legacy = mapper.writeValueAsString(initial).replace(",\"consumed\":false", "");
        assertThat(legacy).doesNotContain("consumed");
        redis.opsForValue().set(key, legacy, Duration.ofMinutes(3));
        assertThat(succeed(adapter)).isEqualTo(VERIFIED);
        assertThat(succeed(adapter)).isEqualTo(EXPIRED);
    }

    @Test
    void challengeSaveFailureDoesNotRestoreOtp() {
        AuthChallengeRedisAdapter challenges = mock(AuthChallengeRedisAdapter.class);
        doThrow(new IllegalStateException("synthetic storage failure")).when(challenges).save(any(), any());
        ConfirmSmsVerificationService service = new ConfirmSmsVerificationService(identity, policy, adapter, challenges);
        assertThatThrownBy(() -> service.confirm(new ConfirmSmsVerificationCommand(
                initial.phoneNumber(), initial.verificationRequestId(), "123456")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(succeed(adapter)).isEqualTo(EXPIRED);
        assertCooldown();
    }

    private void saveState(SmsOtpState state, Duration ttl) {
        redis.opsForValue().set(key, mapper.writeValueAsString(state), ttl);
    }

    private java.util.Optional<SmsOtpState> storedState() {
        String value = redis.opsForValue().get(key);
        return value == null ? java.util.Optional.empty()
                : java.util.Optional.of(mapper.readValue(value, SmsOtpState.class));
    }

    private void assertCooldown() {
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(180000);
        SmsSenderPort sender = mock(SmsSenderPort.class);
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, adapter, sender);
        assertThatThrownBy(() -> service.send(new SendSmsVerificationCommand(initial.phoneNumber())))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(AuthStatusCode.SMS_RESEND_TOO_SOON));
        verifyNoInteractions(sender);
    }

    private SmsOtpState.Outcome fail(SmsVerificationRedisAdapter target) {
        return target.verifyAndConsume(phoneHash, initial.verificationRequestId(), "wrong-hash", 5);
    }

    private SmsOtpState.Outcome succeed(SmsVerificationRedisAdapter target) {
        return target.verifyAndConsume(phoneHash, initial.verificationRequestId(), initial.codeHash(), 5);
    }

    private SmsVerificationRedisAdapter synchronizedReads(int count) throws Exception {
        CountDownLatch reads = new CountDownLatch(count);
        return interceptReads(count, () -> {
            reads.countDown();
            if (!reads.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent reads timed out");
        });
    }

    private SmsVerificationRedisAdapter delayedRead(CountDownLatch read, CountDownLatch release) throws Exception {
        return interceptReads(1, () -> {
            read.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Delayed read timed out");
        });
    }

    @SuppressWarnings("unchecked")
    private SmsVerificationRedisAdapter interceptReads(int count, Interruptible action) throws Exception {
        StringRedisTemplate template = spy(redis);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        doReturn(values).when(template).opsForValue();
        AtomicInteger reads = new AtomicInteger();
        when(values.get(anyString())).thenAnswer(invocation -> {
            String value = redis.opsForValue().get((String) invocation.getArgument(0));
            if (reads.incrementAndGet() <= count) action.run();
            return value;
        });
        return new SmsVerificationRedisAdapter(template, mapper);
    }

    private <T> List<T> race(List<Callable<T>> calls) throws Exception {
        try (var executor = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(executor::submit).toList();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(10, TimeUnit.SECONDS));
            return results;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private interface Interruptible {
        void run() throws InterruptedException;
    }
}
