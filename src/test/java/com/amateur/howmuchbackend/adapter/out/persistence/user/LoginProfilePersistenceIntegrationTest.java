package com.amateur.howmuchbackend.adapter.out.persistence.user;

import com.amateur.howmuchbackend.adapter.out.storage.LocalProfileImageStorageAdapter;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.model.LoginAttempt;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.auth.service.AuthTokenService;
import com.amateur.howmuchbackend.application.auth.service.LoginService;
import com.amateur.howmuchbackend.application.port.in.auth.command.LoginCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;
import com.amateur.howmuchbackend.application.port.in.user.command.UpdateMyProfileCommand;
import com.amateur.howmuchbackend.application.port.out.auth.LoginAttemptStorePort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.user.policy.ProfileImagePolicy;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.application.user.service.UpdateMyProfileService;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.profile.config.ProfileImageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class LoginProfilePersistenceIntegrationTest {
    private static final LocalDateTime BEFORE = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDate BIRTH_DATE = LocalDate.of(1995, 3, 14);
    private static final String PHONE = "01000000301";
    private static final String PIN = "246802";
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Autowired private UserPersistenceAdapter users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @TempDir Path imageDirectory;

    private TransactionTemplate tx;
    private TransactionTemplate separateTx;
    private LocalProfileImageStorageAdapter images;
    private UpdateMyProfileService profiles;
    private PasswordEncoder encoder;
    private AuthTokenService tokens;
    private LoginService login;
    private Long userId;
    private String oldAvatar;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        separateTx = new TransactionTemplate(transactionManager);
        separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        images = new LocalProfileImageStorageAdapter(new ProfileImageProperties(imageDirectory.toString()));
        oldAvatar = images.save(new ProfileImagePolicy().validate(PNG));
        String unique = UUID.randomUUID().toString().replace("-", "");
        userId = tx.execute(status -> users.save(User.register(
                unique, "로그인 테스트", BIRTH_DATE, "old_" + unique.substring(0, 12), PHONE,
                "synthetic-pin-hash", BEFORE, false
        ).changeProfile("old_" + unique.substring(0, 12), oldAvatar, BEFORE)).id());
        assertThat(readUser().birthDate()).isEqualTo(BIRTH_DATE);
        var actor = mock(CurrentActorPort.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(userId));
        profiles = new UpdateMyProfileService(actor, users, users,
                images, new PublicIdPolicy(), new ProfileImagePolicy());
        var attempts = mock(LoginAttemptStorePort.class);
        when(attempts.reserve(anyString(), anyInt(), any())).thenReturn(Optional.of(new LoginAttempt("test", 1)));
        encoder = mock(PasswordEncoder.class);
        when(encoder.matches(PIN, "synthetic-pin-hash")).thenReturn(true);
        tokens = mock(AuthTokenService.class);
        when(tokens.issueNewSession(any())).thenReturn(new TokenResult("access", "request"));
        login = new LoginService(new PinPolicy(5, Duration.ofMinutes(30)), new IdentityPolicy("test-only"),
                attempts, users, users, encoder, tokens);
    }

    @AfterEach
    void cleanUp() {
        if (userId != null) jdbc.update("delete from users where id = ?", userId);
    }

    @ParameterizedTest(name = "프로필 커밋 후 로그인 저장: 기본 이미지 복원={0}")
    @ValueSource(booleans = {false, true})
    void loginProfile_deletedImage_afterCommit_neverRestored(boolean resetAvatar) {
        User before = readUser();
        // PIN 검증이 시작되기 전에 로그인은 이미 관리 중인 사용자 엔티티를 조회한다.
        // REQUIRES_NEW는 별도 연결에서 커밋해도 기존 엔티티가 영속성 컨텍스트에 남는 상황을 만든다.
        when(encoder.matches(PIN, "synthetic-pin-hash")).thenAnswer(call -> {
            separateTx.executeWithoutResult(status -> profiles.update(
                    new UpdateMyProfileCommand("changed_301", resetAvatar ? null : PNG, resetAvatar)));
            assertThat(images.load(oldAvatar)).isEmpty();
            return true;
        });

        TokenResult result = tx.execute(status -> login.login(new LoginCommand(PHONE, PIN)));
        assertThat(result)
                .isEqualTo(new TokenResult("access", "request"));

        User after = readUser();
        assertThat(after.publicId()).isEqualTo("changed_301");
        assertThat(after.avatarFileName()).isNotEqualTo(oldAvatar);
        if (resetAvatar) {
            assertThat(after.avatarFileName()).isNull();
        } else {
            assertThat(images.load(after.avatarFileName())).isPresent();
        }
        assertThat(images.load(oldAvatar)).isEmpty();
        assertThat(after.lastLoginAt()).isAfter(BEFORE);
        assertThat(after.updatedAt()).isEqualTo(after.lastLoginAt());
        assertThat(after).usingRecursiveComparison()
                .ignoringFields("publicId", "avatarFileName", "lastLoginAt", "updatedAt")
                .isEqualTo(before);
    }

    @Test
    void loginProfile_updateAfterLogin_preservesLoginTime() {
        tx.executeWithoutResult(status -> login.login(new LoginCommand(PHONE, PIN)));
        LocalDateTime loggedInAt = readUser().lastLoginAt();
        tx.executeWithoutResult(status -> profiles.update(new UpdateMyProfileCommand("changed_301", null, true)));
        User after = readUser();
        assertThat(after.lastLoginAt()).isEqualTo(loggedInAt);
        assertThat(after.publicId()).isEqualTo("changed_301");
        assertThat(after.avatarFileName()).isNull();
    }

    @Test
    void loginSession_success_recordsTimestampsAndClearsContext() {
        User before = readUser();
        LocalDateTime now = BEFORE.plusDays(1);
        tx.executeWithoutResult(status -> {
            users.findById(userId).orElseThrow();
            users.recordLogin(userId, now);
            assertThat(users.findById(userId).orElseThrow().lastLoginAt()).isEqualTo(now);
        });
        assertThat(readUser()).isEqualTo(before.markLoggedIn(now));
    }

    @Test
    void login_wrongPin_doesNotRecordOrIssueTokens() {
        User before = readUser();
        when(encoder.matches(PIN, "synthetic-pin-hash")).thenReturn(false);
        assertThatThrownBy(() -> tx.execute(status -> login.login(new LoginCommand(PHONE, PIN))))
                .isInstanceOf(ApplicationException.class);
        assertThat(readUser()).isEqualTo(before);
        verifyNoInteractions(tokens);
    }

    @Test
    void login_missingUser_doesNotSucceed() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> users.recordLogin(-1L, BEFORE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void loginSession_failure_rollsBackTimestamps() {
        User before = readUser();
        when(tokens.issueNewSession(any())).thenThrow(new IllegalStateException("synthetic session failure"));
        assertThatThrownBy(() -> tx.execute(status -> login.login(new LoginCommand(PHONE, PIN))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(readUser()).isEqualTo(before);
    }

    private User readUser() {
        return tx.execute(status -> users.findById(userId).orElseThrow());
    }
}
