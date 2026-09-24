package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 삭제 영속 통합")
class SettlementDeletionPersistenceIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 19, 0);
    private static final List<String> CASCADED_TABLES = List.of(
            "settlement_participants",
            "settlement_expenses",
            "settlement_items",
            "settlement_item_selections",
            "settlement_item_submissions",
            "settlement_dutch_pay_allocations",
            "settlement_links"
    );

    @Autowired
    private SettlementPersistenceAdapter settlementPersistenceAdapter;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID settlementId;
    private UUID participantId;
    private UUID expenseId;
    private UUID itemId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        Long creatorId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, '삭제 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "settlement-delete-" + uniqueKey,
                "delete_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
        settlementId = UUID.randomUUID();
        participantId = UUID.randomUUID();
        expenseId = UUID.randomUUID();
        itemId = UUID.randomUUID();
        settlementJpaRepository.saveAndFlush(new SettlementJpaEntity(new Settlement(
                settlementId,
                creatorId,
                "삭제 cascade 검증",
                10_000,
                SettlementAllocationType.ITEM_ALLOCATION,
                SettlementStatus.REQUESTED,
                NOW,
                NOW
        )));

        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, linked_user_id, display_name,
                            display_order, created_at, updated_at
                        ) VALUES (?, ?, 'REGISTERED_USER', ?, '삭제 테스트', 0, ?, ?)
                        """,
                participantId, settlementId, creatorId, NOW, NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_expenses (
                            id, settlement_id, source, description, amount,
                            display_order, created_at, updated_at
                        ) VALUES (?, ?, 'MANUAL', '삭제 지출', 10000, 0, ?, ?)
                        """,
                expenseId, settlementId, NOW, NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_items (
                            id, settlement_id, expense_id, name, quantity, amount,
                            display_order, created_at, updated_at
                        ) VALUES (?, ?, ?, '삭제 품목', 1, 10000, 0, ?, ?)
                        """,
                itemId, settlementId, expenseId, NOW, NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_item_selections (
                            id, settlement_id, item_id, participant_id, fraction,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'ONE', ?, ?)
                        """,
                UUID.randomUUID(), settlementId, itemId, participantId, NOW, NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_item_submissions (
                            id, settlement_id, participant_id, submitted_at
                        ) VALUES (?, ?, ?, ?)
                        """,
                UUID.randomUUID(), settlementId, participantId, NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_dutch_pay_allocations (
                            id, settlement_id, participant_id, amount, created_at, updated_at
                        ) VALUES (?, ?, ?, 10000, ?, ?)
                        """,
                UUID.randomUUID(), settlementId, participantId, NOW, NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_links (
                            id, settlement_id, token_hash, status, expires_at,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                        """,
                UUID.randomUUID(), settlementId, "a".repeat(64), NOW.plusDays(1), NOW, NOW
        );
    }

    @Test
    @DisplayName("정산 root 삭제가 모든 정산 소유 하위 행을 cascade로 제거한다")
    void settlementDeletion_rootRemoved_cascadesOwnedRows() {
        settlementPersistenceAdapter.delete(settlementId);
        settlementJpaRepository.flush();

        assertThat(settlementJpaRepository.existsById(settlementId)).isFalse();
        for (String table : CASCADED_TABLES) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM %s WHERE settlement_id = ?".formatted(table),
                    Long.class,
                    settlementId
            )).as(table).isZero();
        }
    }
}
