package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadMySettlementCategoryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveMySettlementCategoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("내 카테고리 조회·저장 저장소")
class SettlementUserCategoryJdbcAdapterIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 3, 0);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private LoadMySettlementCategoryPort port;
    @Autowired private SaveMySettlementCategoryPort savePort;

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

    @Test
    @DisplayName("처음 고르면 행을 만들고, 다시 고르면 그 회원 행만 덮어쓰며 생성 시각은 그대로다")
    void save_insertsThenOverwritesOwnRowOnly() {
        UUID settlementId = settlement();
        classify(settlementId, "CAFE_SNACK", "RULE");
        override(settlementId, creator, "LIVING");
        LocalDateTime later = NOW.plusHours(1);

        savePort.save(settlementId, member, "FOOD", NOW);
        savePort.save(settlementId, member, "UNCATEGORIZED", later);
        savePort.save(settlementId, member, "UNCATEGORIZED", later);

        assertThat(row(settlementId, member))
                .containsEntry("category_code", "UNCATEGORIZED")
                .containsEntry("created_at", Timestamp.valueOf(NOW))
                .containsEntry("updated_at", Timestamp.valueOf(later));
        assertThat(row(settlementId, creator)).containsEntry("category_code", "LIVING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM settlement_user_categories WHERE settlement_id = ?",
                Integer.class, settlementId)).isEqualTo(2);
        assertThat(port.findCategoryCodes(member, List.of(settlementId)))
                .containsExactlyEntriesOf(Map.of(settlementId, "UNCATEGORIZED"));
        assertThat(port.findCategoryCodes(creator, List.of(settlementId)))
                .containsExactlyEntriesOf(Map.of(settlementId, "LIVING"));
    }

    @Test
    @DisplayName("덮어쓸 때 시각이 생성 시각보다 이르면 수정 시각을 생성 시각으로 맞춰 DB 제약을 지킨다")
    void save_clockMovedBackward_keepsUpdatedAtNotBeforeCreatedAt() {
        UUID settlementId = settlement();
        savePort.save(settlementId, member, "FOOD", NOW);

        savePort.save(settlementId, member, "LIVING", NOW.minusMinutes(5));

        assertThat(row(settlementId, member))
                .containsEntry("category_code", "LIVING")
                .containsEntry("created_at", Timestamp.valueOf(NOW))
                .containsEntry("updated_at", Timestamp.valueOf(NOW));
    }

    private Map<String, Object> row(UUID settlementId, Long userId) {
        return jdbc.queryForMap("""
                SELECT category_code, created_at, updated_at
                FROM settlement_user_categories
                WHERE settlement_id = ? AND user_id = ?
                """, settlementId, userId);
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
