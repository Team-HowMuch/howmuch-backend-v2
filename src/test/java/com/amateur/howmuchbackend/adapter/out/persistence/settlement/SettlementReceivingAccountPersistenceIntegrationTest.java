package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 수령 계좌 영속 매핑")
class SettlementReceivingAccountPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 10, 0);

    @Autowired private SettlementJpaRepository settlementJpaRepository;
    @Autowired private SettlementPersistenceAdapter settlementPersistenceAdapter;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private Long connectionId;
    private Long accountId;
    private UUID settlementId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        creatorId = insertUser(uniqueKey);
        connectionId = insertConnection(creatorId, uniqueKey);
        accountId = insertAccount(creatorId, connectionId, uniqueKey);
        settlementId = UUID.randomUUID();
        settlementJpaRepository.saveAndFlush(new SettlementJpaEntity(new Settlement(
                settlementId,
                creatorId,
                "회식 정산",
                10_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        )));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account_records WHERE id = ?", accountId);
        jdbcTemplate.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
    }

    @Test
    @DisplayName("V15 수령 계좌 ID를 저장하고 Domain으로 다시 복원한다")
    void savesAndLoadsReceivingAccountId() {
        Settlement selected = settlementPersistenceAdapter.findById(settlementId)
                .orElseThrow()
                .selectReceivingAccount(accountId, CREATED_AT.plusMinutes(1));

        settlementPersistenceAdapter.update(selected);

        Settlement loaded = settlementPersistenceAdapter.findById(settlementId).orElseThrow();
        assertThat(loaded.receivingAccountId()).isEqualTo(accountId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT receiving_account_id FROM settlements WHERE id = ?",
                Long.class,
                settlementId
        )).isEqualTo(accountId);
    }

    private Long insertUser(String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "receiving-account-" + uniqueKey,
                "수령 계좌 테스트",
                "receive_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private Long insertConnection(Long userId, String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO external_connections (
                            user_id, connected_id_ciphertext, configuration_fingerprint,
                            active, created_at, updated_at
                        )
                        VALUES (?, ?, ?, TRUE, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                "ciphertext-" + uniqueKey,
                "a".repeat(64),
                CREATED_AT,
                CREATED_AT
        );
    }

    private Long insertAccount(Long userId, Long externalConnectionId, String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO accounts (
                            user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number,
                            balance, currency, active, product_code, is_default,
                            display_order, created_at, updated_at
                        )
                        VALUES (?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', ?, '****6789',
                                1000000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                externalConnectionId,
                "candidate-" + uniqueKey,
                "ciphertext-" + uniqueKey,
                "생활비 통장",
                CREATED_AT,
                CREATED_AT
        );
    }
}
