package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
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
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@DisplayName("정산 요청 V16 영속 흐름")
class SettlementRequestPersistenceIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 10, 0);
    private static final String ACCOUNT_NUMBER = "1002123456789";

    @Autowired private SettlementRequestPersistenceAdapter adapter;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private Long connectionId;
    private Long accountId;
    private UUID settlementId;
    private UUID creatorParticipantId;
    private UUID payerParticipantId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        creatorId = insertUser(uniqueKey);
        connectionId = insertConnection(creatorId, uniqueKey);
        accountId = insertAccount(creatorId, connectionId, uniqueKey);
        settlementId = UUID.randomUUID();
        creatorParticipantId = UUID.randomUUID();
        payerParticipantId = UUID.randomUUID();
        insertSettlement();
        insertParticipants();
    }

    @Test
    @DisplayName("계좌번호는 암호화하고 참여자별 Share와 token hash만 저장한다")
    void savesEncryptedSnapshotSharesAndHashedLink() {
        SettlementReceivingAccountSnapshot snapshot =
                SettlementReceivingAccountSnapshot.create(
                        settlementId,
                        accountId,
                        "0020",
                        ACCOUNT_NUMBER,
                        "최수아",
                        NOW
                );
        List<SettlementShare> shares = List.of(
                SettlementShare.create(settlementId, creatorParticipantId, 4_000, NOW),
                SettlementShare.create(settlementId, payerParticipantId, 6_000, NOW)
        );
        SettlementLink link = SettlementLink.active(
                settlementId,
                "a".repeat(64),
                NOW.plusDays(30),
                NOW
        );

        adapter.save(snapshot, shares, link);

        String ciphertext = jdbcTemplate.queryForObject(
                """
                SELECT account_number_ciphertext
                FROM settlement_receiving_account_snapshots
                WHERE settlement_id = ?
                """,
                String.class,
                settlementId
        );
        assertThat(ciphertext).isNotEqualTo(ACCOUNT_NUMBER);
        assertThat(cipher.decryptAccountKey(creatorId, connectionId, ciphertext))
                .isEqualTo(ACCOUNT_NUMBER);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM settlement_shares WHERE settlement_id = ?",
                Integer.class,
                settlementId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT token_hash FROM settlement_links WHERE settlement_id = ?",
                String.class,
                settlementId
        )).isEqualTo("a".repeat(64));
    }

    private Long insertUser(String uniqueKey) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, '최수아', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "request-" + uniqueKey,
                "request_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                NOW,
                NOW,
                NOW
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
                "b".repeat(64),
                NOW,
                NOW
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
                        VALUES (?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '생활비 통장',
                                '****6789', 1000000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                externalConnectionId,
                "candidate-" + uniqueKey,
                "source-ciphertext-" + uniqueKey,
                NOW,
                NOW
        );
    }

    private void insertSettlement() {
        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, allocation_type,
                            receiving_account_id, status, version, created_at, updated_at
                        )
                        VALUES (?, ?, '회식 정산', 10000, 'DUTCH_PAY', ?, 'DRAFT', 0, ?, ?)
                        """,
                settlementId,
                creatorId,
                accountId,
                NOW,
                NOW
        );
    }

    private void insertParticipants() {
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, linked_user_id, display_name,
                            display_order, created_at, updated_at
                        )
                        VALUES (?, ?, 'REGISTERED_USER', ?, '최수아', 0, ?, ?)
                        """,
                creatorParticipantId,
                settlementId,
                creatorId,
                NOW,
                NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, phone_hash, display_name,
                            display_order, created_at, updated_at
                        )
                        VALUES (?, ?, 'CONTACT', ?, '민선영', 1, ?, ?)
                        """,
                payerParticipantId,
                settlementId,
                "c".repeat(64),
                NOW,
                NOW
        );
    }
}
