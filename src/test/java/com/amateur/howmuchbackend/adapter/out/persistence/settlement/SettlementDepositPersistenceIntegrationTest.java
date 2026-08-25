package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 입금 후보 영속 통합")
class SettlementDepositPersistenceIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 0, 0);

    @Autowired
    private SettlementDepositPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private Long payerId;
    private Long sourceAccountId;
    private List<UUID> settlementIds;

    @BeforeEach
    void setUp() {
        creatorId = insertUser("creator");
        payerId = insertUser("payer");
        sourceAccountId = Math.max(1L, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        jdbcTemplate.update("""
                INSERT INTO account_records (
                    id, user_id, organization, type, name, masked_number, currency,
                    linked_at, deleted_at
                ) VALUES (?, ?, '0020', 'DEPOSIT_TRUST', '수금 계좌', '****6789', 'KRW', ?, NULL)
                """, sourceAccountId, creatorId, NOW);

        settlementIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        settlementIds.forEach(this::insertAwaitingSettlement);
    }

    @AfterEach
    void tearDown() {
        if (settlementIds != null) {
            settlementIds.forEach(settlementId ->
                    jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId));
        }
        if (sourceAccountId != null) {
            jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", sourceAccountId);
        }
        if (payerId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", payerId);
        }
        if (creatorId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
        }
    }

    @Test
    @DisplayName("같은 수금 계좌를 쓰는 모든 입금 대기 정산의 미완료 Share를 함께 조회한다")
    void loadsCompetingCandidatesAcrossSettlements() {
        var candidates = adapter.findUnfinishedPayableCandidatesBySourceAccountId(sourceAccountId);

        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(candidate -> candidate.settlementId())
                .containsExactlyInAnyOrderElementsOf(settlementIds);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.payerName()).isEqualTo("민선영");
            assertThat(candidate.remainingAmount()).isEqualTo(6_000);
        });
    }

    private Long insertUser(String prefix) {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                            role, active, required_terms_agreed_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "settlement-deposit-" + prefix + "-" + unique,
                prefix,
                prefix + "_" + unique.replace("-", "").substring(0, 12),
                prefix + "-" + unique.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
    }

    private void insertAwaitingSettlement(UUID settlementId) {
        UUID participantId = UUID.randomUUID();
        UUID shareId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO settlements (
                    id, creator_id, title, total_amount, status, created_at, updated_at
                ) VALUES (?, ?, '회식 정산', 10000, 'AWAITING_DEPOSIT', ?, ?)
                """, settlementId, creatorId, NOW, NOW);
        jdbcTemplate.update("""
                INSERT INTO settlement_participants (
                    id, settlement_id, source, linked_user_id, phone_hash, display_name,
                    display_order, created_at, updated_at
                ) VALUES (?, ?, 'REGISTERED_USER', ?, NULL, '민선영', 0, ?, ?)
                """, participantId, settlementId, payerId, NOW, NOW);
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization, account_number_ciphertext,
                    account_holder, created_at
                ) VALUES (?, ?, '0020', 'test-ciphertext', '생성자', ?)
                """, settlementId, sourceAccountId, NOW);
        jdbcTemplate.update("""
                INSERT INTO settlement_shares (
                    id, settlement_id, payer_participant_id, amount, created_at
                ) VALUES (?, ?, ?, 6000, ?)
                """, shareId, settlementId, participantId, NOW);
    }
}
