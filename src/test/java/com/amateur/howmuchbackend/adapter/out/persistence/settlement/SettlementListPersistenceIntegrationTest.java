package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementParticipantJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementShareJpaEntity;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementShareJpaRepository;
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
@DisplayName("정산 목록 영속 조회")
class SettlementListPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 13, 12, 0);

    @Autowired
    private SettlementPersistenceAdapter settlementPersistenceAdapter;

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
    private Long otherUserId;
    private Long connectionId;
    private Long accountId;
    private UUID receiveSettlementId;
    private UUID paySettlementId;
    private UUID payPayerParticipantId;

    @BeforeEach
    void setUp() {
        cleanupFixtures();
        String uniqueKey = UUID.randomUUID().toString().replace("-", "");
        creatorId = insertUser("list-creator-" + uniqueKey, "받는 사람");
        payerId = insertUser("list-payer-" + uniqueKey, "낼 사람");
        otherUserId = insertUser("list-other-" + uniqueKey, "다른 사람");
        connectionId = insertConnection(otherUserId, uniqueKey);
        accountId = insertAccount(otherUserId, connectionId, uniqueKey);

        receiveSettlementId = UUID.randomUUID();
        paySettlementId = UUID.randomUUID();
        settlementJpaRepository.saveAllAndFlush(java.util.List.of(
                new SettlementJpaEntity(new Settlement(
                        receiveSettlementId,
                        creatorId,
                        "받을 정산",
                        600L,
                        SettlementStatus.AWAITING_DEPOSIT,
                        CREATED_AT,
                        CREATED_AT
                )),
                new SettlementJpaEntity(new Settlement(
                        paySettlementId,
                        otherUserId,
                        "낼 정산",
                        6_000L,
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
                receiveSettlementId, otherUserId, "다른 사람", 1, CREATED_AT
        );
        SettlementParticipant payCreator = SettlementParticipant.registeredUser(
                paySettlementId, otherUserId, "다른 사람", 0, CREATED_AT
        );
        SettlementParticipant payPayer = SettlementParticipant.registeredUser(
                paySettlementId, payerId, "낼 사람", 1, CREATED_AT
        );
        payPayerParticipantId = payPayer.id();
        settlementParticipantJpaRepository.saveAllAndFlush(java.util.List.of(
                new SettlementParticipantJpaEntity(receiveCreator),
                new SettlementParticipantJpaEntity(receivePayer),
                new SettlementParticipantJpaEntity(payCreator),
                new SettlementParticipantJpaEntity(payPayer)
        ));

        jdbcTemplate.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                )
                VALUES (?, ?, '0020', 'test-snapshot', '다른 사람', ?)
                """, paySettlementId, accountId, CREATED_AT);

        settlementShareJpaRepository.saveAndFlush(new SettlementShareJpaEntity(
                UUID.randomUUID(),
                paySettlementId,
                payPayerParticipantId,
                6_000L,
                CREATED_AT
        ));
    }

    @AfterEach
    void tearDown() {
        cleanupFixtures();
    }

    @Test
    @DisplayName("RECEIVE는 생성자 정산의 총액과 참여자 수를 조회한다")
    void loadsReceiveSettlementSummary() {
        var result = settlementPersistenceAdapter.findAll(
                creatorId,
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS
        );

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(receiveSettlementId);
            assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
            assertThat(item.totalAmount()).isEqualTo(600L);
            assertThat(item.participantCount()).isEqualTo(2);
            assertThat(item.completedParticipantCount()).isNull();
            assertThat(item.amountToPay()).isNull();
        });
    }

    @Test
    @DisplayName("PAY는 연결 참여자의 Share 금액과 요청자 이름을 조회한다")
    void loadsPaySettlementSummary() {
        var result = settlementPersistenceAdapter.findAll(
                payerId,
                SettlementListTab.PAY,
                SettlementListStatusFilter.AWAITING_DEPOSIT
        );

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.settlementId()).isEqualTo(paySettlementId);
            assertThat(item.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
            assertThat(item.amountToPay()).isEqualTo(6_000L);
            assertThat(item.requesterName()).isEqualTo("다른 사람");
            assertThat(item.totalAmount()).isNull();
            assertThat(item.participantCount()).isNull();
        });
    }

    private Long insertUser(String identityKey, String name) {
        String rolePrefix = identityKey.contains("creator")
                ? "c"
                : identityKey.contains("payer") ? "p" : "o";
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
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM accounts
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM account_records
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM external_connections
                 WHERE user_id IN (
                     SELECT id FROM users WHERE identity_key LIKE 'list-%'
                 )
                """);
        jdbcTemplate.update("DELETE FROM users WHERE identity_key LIKE 'list-%'");
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
                "list-connection-" + uniqueKey,
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
                "list-account-" + uniqueKey,
                "list-account-ciphertext-" + uniqueKey,
                CREATED_AT,
                CREATED_AT
        );
    }
}
