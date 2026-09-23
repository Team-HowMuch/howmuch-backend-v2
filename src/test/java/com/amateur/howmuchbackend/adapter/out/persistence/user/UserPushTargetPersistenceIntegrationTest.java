package com.amateur.howmuchbackend.adapter.out.persistence.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("사용자 앱 푸시 대상 영속 통합")
class UserPushTargetPersistenceIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 29, 12, 0);
    private static final String TARGET = "firebase-installation-id";

    @Autowired private UserPersistenceAdapter userPersistenceAdapter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    @DisplayName("같은 FID를 새 계정에 등록하면 기존 계정에서 현재 계정으로 이전한다")
    void pushTarget_sameFid_transfersToNewOwner() {
        Long previousUserId = insertUser(TARGET);
        Long currentUserId = insertUser(null);

        boolean claimed = inTransaction(() ->
                userPersistenceAdapter.claim(currentUserId, TARGET, NOW.plusMinutes(1))
        );

        assertThat(claimed).isTrue();
        assertThat(findTarget(previousUserId)).isNull();
        assertThat(findTarget(currentUserId)).isEqualTo(TARGET);
    }

    @Test
    @DisplayName("현재 계정의 FID를 해제한다")
    void clearsTarget() {
        Long userId = insertUser(TARGET);

        boolean cleared = inTransaction(() ->
                userPersistenceAdapter.clear(userId, NOW.plusMinutes(1))
        );

        assertThat(cleared).isTrue();
        assertThat(findTarget(userId)).isNull();
    }

    private boolean inTransaction(java.util.function.Supplier<Boolean> action) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }

    private String findTarget(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT fcm_token FROM users WHERE id = ?",
                String.class,
                userId
        );
    }

    private Long insertUser(String fcmToken) {
        String uniqueKey = UUID.randomUUID().toString();
        Long id = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key,
                            name,
                            public_id,
                            phone_number,
                            pin_hash,
                            fcm_token,
                            phone_verified,
                            role,
                            active,
                            required_terms_agreed_at,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "push-target-" + uniqueKey,
                "푸시 대상 테스트",
                "push_" + uniqueKey.replace("-", "").substring(0, 12),
                "push-" + uniqueKey.substring(0, 12),
                "not-used",
                fcmToken,
                NOW,
                NOW,
                NOW
        );
        userIds.add(id);
        return id;
    }
}
