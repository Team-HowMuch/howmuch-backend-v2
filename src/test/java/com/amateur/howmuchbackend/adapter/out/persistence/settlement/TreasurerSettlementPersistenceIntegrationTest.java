package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementShareJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
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
@DisplayName("총무 정산 영속 조회")
class TreasurerSettlementPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 13, 12, 0);

    @Autowired
    private TreasurerSettlementPersistenceAdapter treasurerSettlementPersistenceAdapter;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private SettlementParticipantJpaRepository settlementParticipantJpaRepository;

    @Autowired
    private SettlementShareJpaRepository settlementShareJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private Long payerId;
    private UUID receiveSettlementId;
    private UUID paySettlementId;

    @BeforeEach
    void setUp() {
        cleanupFixtures();
        String uniqueKey = UUID.randomUUID().toString().replace("-", "");
        creatorId = insertUser("treasurer-creator-" + uniqueKey, "받는 사람");
        payerId = insertUser("treasurer-payer-" + uniqueKey, "낼 사람");
        Long connectionId = insertConnection(creatorId, uniqueKey);
        Long accountId = insertAccount(creatorId, connectionId, uniqueKey);

        receiveSettlementId = UUID.randomUUID();
        paySettlementId = UUID.randomUUID();
        settlementJpaRepository.saveAllAndFlush(java.util.List.of(
                new SettlementJpaEntity(new Settlement(
                        receiveSettlementId,
                        creatorId,
                        "받을 정산",
                        10_000L,
                        SettlementAllocationType.DUTCH_PAY,
                        accountId,
                        SettlementStatus.AWAITING_DEPOSIT,
                        CREATED_AT,
                        CREATED_AT
                )),
                new SettlementJpaEntity(new Settlement(
                        paySettlementId,
                        creatorId,
                        "낼 정산",
                        5_500L,
                        SettlementAllocationType.DUTCH_PAY,
                        accountId,
                        SettlementStatus.AWAITING_DEPOSIT,
                        CREATED_AT.plusMinutes(1),
                        CREATED_AT.plusMinutes(1)
                ))
        ));

        SettlementParticipant receiveCreator = SettlementParticipant.registeredUser(
                receiveSettlementId, creatorId, "받는 사람", 0, CREATED_AT
        );
        SettlementParticipant receivePayer = SettlementParticipant.registeredUser(
                receiveSettlementId, payerId, "낼 사람", 1, CREATED_AT
        );
        SettlementParticipant payCreator = SettlementParticipant.registeredUser(
                paySettlementId, creatorId, "받는 사람", 0, CREATED_AT
        );
        SettlementParticipant payPayer = SettlementParticipant.registeredUser(
                paySettlementId, payerId, "낼 사람", 1, CREATED_AT
        );
        settlementParticipantJpaRepository.saveAllAndFlush(java.util.List.of(
                new SettlementParticipantJpaEntity(receiveCreator),
                new SettlementParticipantJpaEntity(receivePayer),
                new SettlementParticipantJpaEntity(payCreator),
                new SettlementParticipantJpaEntity(payPayer)
        ));
        insertReceivingAccountSnapshot(receiveSettlementId, accountId);
        insertReceivingAccountSnapshot(paySettlementId, accountId);
        settlementShareJpaRepository.saveAllAndFlush(java.util.List.of(
                new SettlementShareJpaEntity(
                        UUID.randomUUID(), receiveSettlementId, receiveCreator.id(), 4_000L, CREATED_AT
                ),
                new SettlementShareJpaEntity(
                        UUID.randomUUID(), receiveSettlementId, receivePayer.id(), 6_000L, CREATED_AT
                ),
                new SettlementShareJpaEntity(
                        UUID.randomUUID(), paySettlementId, payPayer.id(), 5_500L, CREATED_AT
                )
        ));
    }

    @AfterEach
    void tearDown() {
        cleanupFixtures();
    }

    @Test
    @DisplayName("RECEIVE는 생성자 본인 Share를 제외한 금액을 집계한다")
    void receivableAmount_creatorShare_excluded() {
        var result = treasurerSettlementPersistenceAdapter.findAll(creatorId);

        assertThat(result)
                .filteredOn(item -> item.settlementId().equals(receiveSettlementId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.direction()).isEqualTo(SettlementDirection.RECEIVE);
                    assertThat(item.amount()).isEqualTo(6_000L);
                    assertThat(item.payerCount()).isEqualTo(1);
                    assertThat(item.completedPayerCount()).isZero();
                    assertThat(item.allocationType()).isEqualTo(SettlementAllocationType.DUTCH_PAY);
                    assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
                });
    }

    @Test
    @DisplayName("SEND는 현재 사용자의 Share 금액을 선택한다")
    void loadsAmountToPayForLinkedParticipant() {
        var result = treasurerSettlementPersistenceAdapter.findAll(payerId);

        assertThat(result)
                .filteredOn(item -> item.settlementId().equals(paySettlementId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.direction()).isEqualTo(SettlementDirection.SEND);
                    assertThat(item.amount()).isEqualTo(5_500L);
                    assertThat(item.allocationType()).isEqualTo(SettlementAllocationType.DUTCH_PAY);
                    assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
                });
    }

    private Long insertUser(String identityKey, String name) {
        String rolePrefix = identityKey.contains("creator") ? "c" : "p";
        String uniqueSuffix = identityKey.substring(identityKey.lastIndexOf('-') + 1);
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                identityKey,
                name,
                identityKey.replace("-", "_").substring(0, 20),
                "t" + rolePrefix + uniqueSuffix.substring(0, 13),
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private void cleanupFixtures() {
        jdbcTemplate.update("""
                DELETE FROM settlements
                 WHERE creator_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'treasurer-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM accounts
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'treasurer-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM account_records
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'treasurer-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM external_connections
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'treasurer-%'
                 )
                """);
        jdbcTemplate.update("DELETE FROM users WHERE identity_key LIKE 'treasurer-%'");
    }

    private void insertReceivingAccountSnapshot(UUID settlementId, Long accountId) {
        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                )
                VALUES (?, ?, '0020', 'test-snapshot', '받는 사람', ?)
                """, settlementId, accountId, CREATED_AT);
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
                "treasurer-connection-" + uniqueKey,
                "d".repeat(64),
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
                        VALUES (?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '정산 계좌',
                                '****0000', 1000000, 'KRW', TRUE, '11', TRUE, 0, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                externalConnectionId,
                "treasurer-account-" + uniqueKey,
                "treasurer-account-ciphertext-" + uniqueKey,
                CREATED_AT,
                CREATED_AT
        );
    }
}
