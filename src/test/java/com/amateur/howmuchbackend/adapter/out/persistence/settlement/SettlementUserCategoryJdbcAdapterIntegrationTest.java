package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadMySettlementCategoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("내 카테고리 조회 저장소")
class SettlementUserCategoryJdbcAdapterIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 3, 0);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadMySettlementCategoryPort port;

    private Long creator;
    private Long member;

    @BeforeEach
    void setUp() {
        creator = user("my-cat-creator");
        member = user("my-cat-member");
    }

    @Test
    @DisplayName("회원이 고른 값 → 분류 완료 값 순서로, 회원마다 따로 읽고 분류 전 정산은 뺀다")
    void findCategoryCodes_perUserOverrideThenClassified() {
        UUID classified = settlement();
        classify(classified, "CAFE_SNACK", "RULE");
        override(classified, member, "FOOD");
        UUID uncategorized = settlement();
        classify(uncategorized, "UNCATEGORIZED", "UNCATEGORIZED");
        UUID pending = settlement();
        jdbc.update("""
                INSERT INTO settlement_categories (settlement_id, status, next_attempt_at, created_at, updated_at)
                VALUES (?, 'PENDING', ?, ?, ?)
                """, pending, NOW, NOW, NOW);
        UUID onlyOverride = settlement();
        override(onlyOverride, member, "LIVING");
        UUID none = settlement();
        List<UUID> all = List.of(classified, uncategorized, pending, onlyOverride, none);

        assertThat(port.findCategoryCodes(creator, all)).containsExactlyInAnyOrderEntriesOf(Map.of(
                classified, "CAFE_SNACK",
                uncategorized, "UNCATEGORIZED"));
        assertThat(port.findCategoryCodes(member, all)).containsExactlyInAnyOrderEntriesOf(Map.of(
                classified, "FOOD",
                uncategorized, "UNCATEGORIZED",
                onlyOverride, "LIVING"));
        assertThat(port.findCategoryCodes(member, List.of())).isEmpty();
    }

    private Long user(String prefix) {
        String key = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users (identity_key, name, public_id, phone_number, pin_hash, phone_verified, role,
                    active, required_terms_agreed_at, created_at, updated_at)
                VALUES (?, '내 카테고리', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                RETURNING id
                """, Long.class, prefix + "-" + key, "mc_" + key.replace("-", "").substring(0, 12),
                "mc-" + key.substring(0, 12), NOW, NOW, NOW);
    }

    private UUID settlement() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements (id, creator_id, title, total_amount, allocation_type, status, version,
                    created_at, updated_at)
                VALUES (?, ?, '내 카테고리', 10000, 'DUTCH_PAY', 'AWAITING_DEPOSIT', 0, ?, ?)
                """, id, creator, NOW, NOW);
        return id;
    }

    private void classify(UUID settlementId, String code, String source) {
        jdbc.update("""
                INSERT INTO settlement_categories (settlement_id, status, category_code, source, classified_at,
                    created_at, updated_at)
                VALUES (?, 'CLASSIFIED', ?, ?, ?, ?, ?)
                """, settlementId, code, source, NOW, NOW, NOW);
    }

    private void override(UUID settlementId, Long userId, String code) {
        jdbc.update("""
                INSERT INTO settlement_user_categories (settlement_id, user_id, category_code, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, settlementId, userId, code, NOW, NOW);
    }
}
