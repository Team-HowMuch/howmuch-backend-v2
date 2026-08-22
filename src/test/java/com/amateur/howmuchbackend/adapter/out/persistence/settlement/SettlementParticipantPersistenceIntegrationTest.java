package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.entity.SettlementJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementParticipantJpaRepository;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
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
@DisplayName("정산 참여자 영속 통합")
class SettlementParticipantPersistenceIntegrationTest {
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 22, 12, 0);

    @Autowired
    private SettlementParticipantPersistenceAdapter settlementParticipantPersistenceAdapter;

    @Autowired
    private SettlementParticipantJpaRepository settlementParticipantJpaRepository;

    @Autowired
    private SettlementJpaRepository settlementJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long creatorId;
    private UUID settlementId;

    @BeforeEach
    void setUp() {
        String uniqueKey = UUID.randomUUID().toString();
        String publicId = "part_" + uniqueKey.replace("-", "").substring(0, 12);
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
                "participant-persistence-" + uniqueKey,
                "최수아",
                publicId,
                "test-" + uniqueKey.substring(0, 12),
                "not-used",
                CREATED_AT,
                CREATED_AT,
                CREATED_AT
        );
        settlementId = UUID.randomUUID();
        Settlement settlement = new Settlement(
                settlementId,
                creatorId,
                "참여자 영속 검증",
                10_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
        settlementJpaRepository.saveAndFlush(new SettlementJpaEntity(settlement));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", creatorId);
    }

    @Test
    @DisplayName("생성자 참여자의 식별 정보와 표시 순서를 V9 스키마에 저장한다")
    void savesCreatorParticipant() {
        SettlementParticipant participant = SettlementParticipant.registeredUser(
                settlementId,
                creatorId,
                "최수아",
                0,
                CREATED_AT
        );

        SettlementParticipant saved = settlementParticipantPersistenceAdapter.save(participant);

        var entity = settlementParticipantJpaRepository.findById(saved.id()).orElseThrow();
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();
        assertThat(entity.getSettlementId()).isEqualTo(settlementId);
        assertThat(entity.getSource()).isEqualTo(SettlementParticipantSource.REGISTERED_USER);
        assertThat(entity.getLinkedUserId()).isEqualTo(creatorId);
        assertThat(entity.getPhoneHash()).isNull();
        assertThat(entity.getDisplayName()).isEqualTo("최수아");
        assertThat(entity.getDisplayOrder()).isZero();
    }

    @Test
    @DisplayName("정산이 삭제되면 하위 참여자도 함께 삭제한다")
    void deletesParticipantWithSettlement() {
        SettlementParticipant participant = settlementParticipantPersistenceAdapter.save(
                SettlementParticipant.registeredUser(
                        settlementId,
                        creatorId,
                        "최수아",
                        0,
                        CREATED_AT
                )
        );

        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);

        assertThat(settlementParticipantJpaRepository.existsById(participant.id())).isFalse();
    }

    @Test
    @DisplayName("화면이 저장 순서대로 복원되도록 참여자를 displayOrder 오름차순으로 조회한다")
    void loadsParticipantsInDisplayOrder() {
        settlementParticipantPersistenceAdapter.save(unlinkedContact("c".repeat(64), "세 번째", 2));
        settlementParticipantPersistenceAdapter.save(unlinkedContact("a".repeat(64), "첫 번째", 0));
        settlementParticipantPersistenceAdapter.save(unlinkedContact("b".repeat(64), "두 번째", 1));

        List<SettlementParticipant> participants = settlementParticipantPersistenceAdapter
                .findAllBySettlementId(settlementId);

        assertThat(participants).extracting(SettlementParticipant::displayOrder)
                .containsExactly(0, 1, 2);
        assertThat(participants).extracting(SettlementParticipant::displayName)
                .containsExactly("첫 번째", "두 번째", "세 번째");
    }

    private SettlementParticipant unlinkedContact(String phoneHash, String displayName, int displayOrder) {
        return SettlementParticipant.unlinkedContact(
                settlementId,
                phoneHash,
                displayName,
                displayOrder,
                CREATED_AT
        );
    }
}
