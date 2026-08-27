package com.amateur.howmuchbackend.adapter.out.persistence.user;

import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("사용자 공개 ID 후보 검색 영속 통합")
class UserPublicIdSearchPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 12, 0);

    @Autowired
    private UserPersistenceAdapter userPersistenceAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
    }

    @Test
    @DisplayName("밑줄을 문자 그대로 포함한 prefix로 활성 회원만 정렬·제외·제한 조회한다")
    void searchesActiveUsersByLiteralPublicIdPrefix() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String prefix = "p" + key + "_";
        Long excludedId = insertUser(prefix + "alpha", true);
        insertUser(prefix + "beta", true);
        insertUser(prefix + "gamma", true);
        insertUser("p" + key + "xdelta", true);
        insertUser(prefix + "inactive", false);

        List<User> result = userPersistenceAdapter.searchActiveByPublicIdPrefix(
                prefix,
                Set.of(excludedId),
                2
        );

        assertThat(result).extracting(User::publicId)
                .containsExactly(prefix + "beta", prefix + "gamma");
    }

    @Test
    @DisplayName("연락처 HMAC 목록과 일치하는 활성 회원만 한 번에 조회한다")
    void matchesActiveUsersByIdentityKeys() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String activeIdentityKey = "active-contact-" + key;
        String inactiveIdentityKey = "inactive-contact-" + key;
        insertUser("a" + key, true, activeIdentityKey);
        insertUser("i" + key, false, inactiveIdentityKey);
        insertUser("o" + key, true, "other-contact-" + key);

        List<User> result = userPersistenceAdapter.findActiveByIdentityKeys(Set.of(
                activeIdentityKey,
                inactiveIdentityKey,
                "missing-contact-" + key
        ));

        assertThat(result).extracting(User::identityKey)
                .containsExactly(activeIdentityKey);
    }

    private Long insertUser(String publicId, boolean active) {
        return insertUser(publicId, active, "public-id-search-" + UUID.randomUUID());
    }

    private Long insertUser(String publicId, boolean active, String identityKey) {
        String uniqueKey = UUID.randomUUID().toString();
        Long id = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key,
                            name,
                            public_id,
                            phone_number,
                            pin_hash,
                            phone_verified,
                            role,
                            active,
                            required_terms_agreed_at,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, TRUE, 'USER', ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                identityKey,
                "검색 후보",
                publicId,
                "search-" + uniqueKey.substring(0, 12),
                "not-used",
                active,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        userIds.add(id);
        return id;
    }
}
