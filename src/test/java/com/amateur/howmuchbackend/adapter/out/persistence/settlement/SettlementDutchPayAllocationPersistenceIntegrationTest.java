package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementDutchPayAllocationJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("더치페이 초안 영속 통합")
class SettlementDutchPayAllocationPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 18, 0);

    @Autowired
    private SettlementDutchPayAllocationPersistenceAdapter adapter;
    @Autowired
    private SettlementDutchPayAllocationJpaRepository allocationRepository;
    @Autowired
    private SettlementJpaRepository settlementRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private UUID settlementId;
    private UUID creatorParticipantId;
    private UUID contactParticipantId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        creatorId = jdbcTemplate.queryForObject("""
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
                        VALUES (?, ?, ?, ?, ?, TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "allocation-persistence-" + uniqueKey,
                "배분 테스트",
                "alloc_" + uniqueKey.replace("-", "").substring(0, 12),
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        settlementId = UUID.randomUUID();
        settlementRepository.saveAndFlush(new SettlementJpaEntity(new Settlement(
                settlementId,
                creatorId,
                "더치페이 영속 검증",
                1_000,
                SettlementAllocationType.DUTCH_PAY,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        )));
        creatorParticipantId = UUID.randomUUID();
        contactParticipantId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, linked_user_id, phone_hash,
                            display_name, display_order, created_at, updated_at
                        ) VALUES (?, ?, 'REGISTERED_USER', ?, NULL, ?, 0, ?, ?)
                        """,
                creatorParticipantId,
                settlementId,
                creatorId,
                "생성자",
                CREATED_AT,
                CREATED_AT
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, linked_user_id, phone_hash,
                            display_name, display_order, created_at, updated_at
                        ) VALUES (?, ?, 'CONTACT', NULL, ?, ?, 1, ?, ?)
                        """,
                contactParticipantId,
                settlementId,
                "a".repeat(64),
                "친구",
                CREATED_AT,
                CREATED_AT
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
    }

    @Test
    @DisplayName("V12 스키마에 참여자별 초안을 교체·조회·수정한다")
    void dutchPayDraft_participantAmounts_replacedLoadedAndUpdated() {
        List<SettlementDutchPayAllocation> saved = adapter.replaceAll(
                settlementId,
                List.of(
                        allocation(creatorParticipantId, 500),
                        allocation(contactParticipantId, 500)
                )
        );

        assertThat(saved).extracting(SettlementDutchPayAllocation::amount)
                .containsExactly(500L, 500L);
        SettlementDutchPayAllocation contact = adapter
                .findBySettlementIdAndParticipantId(settlementId, contactParticipantId)
                .orElseThrow();
        adapter.update(contact.updateAmount(400, CREATED_AT.plusMinutes(1)));

        assertThat(adapter.findAllBySettlementId(settlementId))
                .extracting(SettlementDutchPayAllocation::amount)
                .containsExactlyInAnyOrder(500L, 400L);
    }

    @Test
    @DisplayName("참여자가 제거되면 복합 외래키의 Cascade로 해당 배분 초안도 제거한다")
    void cascadesWhenParticipantIsDeleted() {
        adapter.replaceAll(
                settlementId,
                List.of(
                        allocation(creatorParticipantId, 500),
                        allocation(contactParticipantId, 500)
                )
        );

        jdbcTemplate.update(
                "DELETE FROM settlement_participants WHERE id = ?",
                contactParticipantId
        );

        assertThat(allocationRepository.findAllBySettlementId(settlementId))
                .extracting(item -> item.getParticipantId())
                .containsExactly(creatorParticipantId);
    }

    private SettlementDutchPayAllocation allocation(UUID participantId, long amount) {
        return SettlementDutchPayAllocation.create(
                settlementId,
                participantId,
                amount,
                CREATED_AT
        );
    }
}
