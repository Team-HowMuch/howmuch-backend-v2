package com.amateur.howmuchbackend.adapter.out.redis;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.auth.service.ConfirmSmsVerificationService;
import com.amateur.howmuchbackend.application.auth.service.SendSmsVerificationService;
import com.amateur.howmuchbackend.application.port.in.auth.command.ConfirmSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.in.auth.command.SendSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.SmsVerificationConfirmedResult;
import com.amateur.howmuchbackend.application.port.out.auth.SmsSenderPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.auth.verification.SmsOtpState;
import com.amateur.howmuchbackend.domain.user.User;
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
import java.util.Optional;
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
    private final LoadUserPort loadUserPort = mock(LoadUserPort.class);
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
            redis.delete(cooldownKey());
            challengeKeys.forEach(redis::delete);
        }
        if (connection != null) connection.destroy();
    }

    @Test
    void smsOtp_concurrentFailures_sameSnapshotCountedAndTtlKept() throws Exception {
        long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
        SmsVerificationRedisAdapter racing = synchronizedReads(2);
        assertThat(race(List.of(() -> fail(racing), () -> fail(racing))))
                .containsExactly(CODE_MISMATCH, CODE_MISMATCH);
        assertThat(storedState().orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(ttl);
    }

    @Test
    void smsOtp_concurrentFailures_atLimitRejectsCorrectCode() throws Exception {
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
    void smsOtp_successRacesFailure_consumedOtpNotRestored() throws Exception {
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
    void smsOtp_concurrentConfirmations_oneChallengeStored() throws Exception {
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
    void smsOtp_staleFailure_doesNotOverwriteReplacement() throws Exception {
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
    void smsOtp_delayedFailure_consumedOtpNotRestored() throws Exception {
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
    void smsOtp_expiresBetweenReadAndTransition_keyNotRecreated() throws Exception {
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
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, adapter, sender, loadUserPort);
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
    void registeredPhoneIsRejectedBeforeRedisReservation() {
        redis.delete(key);
        redis.delete(cooldownKey());
        when(loadUserPort.findByPhoneNumber(initial.phoneNumber())).thenReturn(Optional.of(mock(User.class)));
        SmsSenderPort sender = mock(SmsSenderPort.class);
        SendSmsVerificationService service = new SendSmsVerificationService(
                identity, policy, adapter, sender, loadUserPort);

        assertThatThrownBy(() -> service.send(new SendSmsVerificationCommand(initial.phoneNumber())))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.getStatusCode())
                                .isEqualTo(AuthStatusCode.PHONE_NUMBER_ALREADY_REGISTERED));
        verifyNoInteractions(sender);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey(cooldownKey())).isFalse();
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

    @Test
    void sentOtpExhaustionPreservesCooldownAndNextSendCanBeConfirmed() throws Exception {
        redis.delete(key);
        SmsSenderPort sender = mock(SmsSenderPort.class);
        List<String> codes = new ArrayList<>();
        doAnswer(call -> { codes.add(call.getArgument(1)); return null; })
                .when(sender).send(anyString(), anyString());
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, adapter, sender, loadUserPort);
        AuthChallengeRedisAdapter challenges = new AuthChallengeRedisAdapter(redis, mapper);
        ConfirmSmsVerificationService confirm = new ConfirmSmsVerificationService(identity, policy, adapter, challenges);
        var first = service.send(new SendSmsVerificationCommand(initial.phoneNumber()));
        long cooldown = redis.getExpire(cooldownKey(), TimeUnit.MILLISECONDS);
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            AuthStatusCode expected = attempt == policy.maxAttempts()
                    ? AuthStatusCode.SMS_VERIFICATION_ATTEMPTS_EXCEEDED
                    : AuthStatusCode.SMS_VERIFICATION_CODE_MISMATCH;
            assertThatThrownBy(() -> confirm.confirm(new ConfirmSmsVerificationCommand(
                    initial.phoneNumber(), first.verificationRequestId(), "000000")))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            error -> assertThat(error.getStatusCode()).isEqualTo(expected));
        }
        assertCooldown();
        assertThat(redis.getExpire(cooldownKey(), TimeUnit.MILLISECONDS))
                .isPositive().isLessThanOrEqualTo(cooldown);
        elapseCooldown();
        var second = service.send(new SendSmsVerificationCommand(initial.phoneNumber()));
        assertThat(second.verificationRequestId()).isNotEqualTo(first.verificationRequestId());
        var confirmed = confirm.confirm(new ConfirmSmsVerificationCommand(
                initial.phoneNumber(), second.verificationRequestId(), codes.getLast()));
        challengeKeys.add("auth:challenge:" + confirmed.challengeId());
        assertThat(challenges.findById(confirmed.challengeId())).isPresent();
        verify(sender, times(2)).send(anyString(), anyString());
    }

    @Test
    void otpExpiryBeforeCooldownDoesNotAllowResend() throws Exception {
        redis.delete(key);
        SmsVerificationPolicy shortOtp = new SmsVerificationPolicy(
                Duration.ofMillis(30), policy.challengeTtl(), Duration.ofSeconds(60), 5);
        SmsSenderPort sender = mock(SmsSenderPort.class);
        SendSmsVerificationService service = new SendSmsVerificationService(identity, shortOtp, adapter, sender, loadUserPort);
        service.send(new SendSmsVerificationCommand(initial.phoneNumber()));
        TimeUnit.MILLISECONDS.sleep(60);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(succeed(adapter)).isEqualTo(EXPIRED);
        assertThatThrownBy(() -> service.send(new SendSmsVerificationCommand(initial.phoneNumber())))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(AuthStatusCode.SMS_RESEND_TOO_SOON));
        verify(sender, times(1)).send(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simultaneousSendsReserveAndDeliverExactlyOnce(boolean legacyExists) throws Exception {
        redis.delete(key);
        if (legacyExists) {
            saveState(new SmsOtpState(initial.verificationRequestId(), initial.phoneNumber(), initial.codeHash(),
                    5, now().minusSeconds(1), now().plusMinutes(3)), Duration.ofMinutes(3));
        }
        SmsSenderPort sender = mock(SmsSenderPort.class);
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, synchronizedReads(8), sender, loadUserPort);
        Callable<Object> send = () -> {
            try { return service.send(new SendSmsVerificationCommand(initial.phoneNumber())); }
            catch (ApplicationException error) { return error.getStatusCode(); }
        };
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(send);
        List<Object> results = race(calls);
        assertThat(results.stream().filter(AuthStatusCode.SMS_RESEND_TOO_SOON::equals).count()).isEqualTo(7);
        verify(sender, times(1)).send(anyString(), anyString());
        assertThat(redis.opsForValue().get(cooldownKey()))
                .isEqualTo(storedState().orElseThrow().verificationRequestId());
    }

    @Test
    void deliveryFailureDiscardsOnlyOtpAndKeepsCooldown() {
        redis.delete(key);
        SmsSenderPort sender = mock(SmsSenderPort.class);
        RuntimeException failure = new IllegalStateException("synthetic delivery failure");
        doThrow(failure).when(sender).send(anyString(), anyString());
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, adapter, sender, loadUserPort);
        assertThatThrownBy(() -> service.send(new SendSmsVerificationCommand(initial.phoneNumber())))
                .isSameAs(failure);
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.getExpire(cooldownKey(), TimeUnit.MILLISECONDS)).isPositive();
        assertThatThrownBy(() -> service.send(new SendSmsVerificationCommand(initial.phoneNumber())))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(AuthStatusCode.SMS_RESEND_TOO_SOON));
        verify(sender, times(1)).send(anyString(), anyString());
    }

    @Test
    void delayedDeliveryFailureCannotDeleteNewRequestOrCooldown() throws Exception {
        redis.delete(key);
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SmsSenderPort delayedSender = (phone, code) -> {
            sending.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Delivery timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            throw new IllegalStateException("synthetic delayed delivery failure");
        };
        SendSmsVerificationService delayed = new SendSmsVerificationService(identity, policy, adapter, delayedSender, loadUserPort);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> delayed.send(new SendSmsVerificationCommand(initial.phoneNumber())));
            try {
                assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
                elapseCooldown();
                SendSmsVerificationService next = new SendSmsVerificationService(
                        identity, policy, adapter, mock(SmsSenderPort.class), loadUserPort);
                var replacement = next.send(new SendSmsVerificationCommand(initial.phoneNumber()));
                release.countDown();
                assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(storedState().orElseThrow().verificationRequestId())
                        .isEqualTo(replacement.verificationRequestId());
                assertThat(redis.opsForValue().get(cooldownKey())).isEqualTo(replacement.verificationRequestId());
                assertCooldown();
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {-3600, 3600})
    void legacyCooldownUsesRedisLifetimeDespiteClockOffset(long offset) {
        saveState(new SmsOtpState(initial.verificationRequestId(), initial.phoneNumber(), initial.codeHash(),
                5, now().plusSeconds(offset + 60), now().plusSeconds(offset + 180)), Duration.ofMinutes(3));
        assertCooldown();
        assertThat(redis.getExpire(cooldownKey(), TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
    }

    @Test
    void consumedOtpKeepsIndependentCooldown() {
        redis.delete(key);
        assertThat(adapter.tryReserve(phoneHash, initial, policy.otpTtl(), policy.resendCooldown())).isTrue();
        assertThat(succeed(adapter)).isEqualTo(VERIFIED);
        redis.delete(key);
        assertThat(adapter.tryReserve(phoneHash, initial, policy.otpTtl(), policy.resendCooldown())).isFalse();
    }

    @Test
    void nonExpiringLegacyOtpDoesNotPermitSend() {
        redis.persist(key);
        assertThat(adapter.tryReserve(phoneHash, initial, policy.otpTtl(), policy.resendCooldown())).isFalse();
    }

    @Test
    void realCooldownExpiryAllowsReplacementWhileOtpRemains() throws Exception {
        redis.delete(key);
        SmsVerificationPolicy shortCooldown = new SmsVerificationPolicy(
                policy.otpTtl(), policy.challengeTtl(), Duration.ofMillis(80), 5);
        SendSmsVerificationService service = new SendSmsVerificationService(
                identity, shortCooldown, adapter, mock(SmsSenderPort.class), loadUserPort);
        var first = service.send(new SendSmsVerificationCommand(initial.phoneNumber()));
        TimeUnit.MILLISECONDS.sleep(120);
        assertThat(redis.hasKey(cooldownKey())).isFalse();
        assertThat(redis.hasKey(key)).isTrue();
        var next = service.send(new SendSmsVerificationCommand(initial.phoneNumber()));
        assertThat(next.verificationRequestId()).isNotEqualTo(first.verificationRequestId());
    }

    @Test
    void legacyJsonWithoutConsumedRetainsCooldown() {
        redis.opsForValue().set(key, mapper.writeValueAsString(initial).replace(",\"consumed\":false", ""),
                policy.otpTtl());
        assertCooldown();
        assertThat(redis.getExpire(cooldownKey(), TimeUnit.MILLISECONDS)).isPositive();
    }

    @Test
    void corruptLegacyStateDoesNotTriggerDelivery() {
        redis.opsForValue().set(key, "invalid-json", policy.otpTtl());
        SmsSenderPort sender = mock(SmsSenderPort.class);
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, adapter, sender, loadUserPort);
        assertThatThrownBy(() -> service.send(new SendSmsVerificationCommand(initial.phoneNumber())))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(sender);
        assertThat(redis.opsForValue().get(key)).isEqualTo("invalid-json");
    }

    private String cooldownKey() {
        return "auth:sms:cooldown:" + phoneHash;
    }

    private void elapseCooldown() throws InterruptedException {
        // Advance both Redis lifetimes consistently without waiting the full production minute.
        SmsOtpState state = storedState().orElseThrow();
        long remainingOtp = Duration.between(state.resendAvailableAt(), state.expiresAt()).toMillis();
        redis.expire(key, Duration.ofMillis(remainingOtp));
        redis.expire(cooldownKey(), Duration.ofMillis(1));
        TimeUnit.MILLISECONDS.sleep(20);
        assertThat(redis.hasKey(cooldownKey())).isFalse();
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
        SendSmsVerificationService service = new SendSmsVerificationService(identity, policy, adapter, sender, loadUserPort);
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
