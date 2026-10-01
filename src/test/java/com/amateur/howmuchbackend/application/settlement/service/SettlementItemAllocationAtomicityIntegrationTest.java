package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementSharePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 품목 배분 원자성 통합")
class SettlementItemAllocationAtomicityIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 10, 0);

    @Autowired private SettlementItemAllocationWriter writer;
    @Autowired private SettlementService settlementService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private SaveSettlementSharePort saveSettlementSharePort;
    @MockitoBean private CurrentActorPort currentActorPort;

    private Long creatorId;
    private Long payerId;
    private UUID settlementId;
    private UUID creatorParticipantId;

    @BeforeEach
    void setUp() {
        creatorId = insertUser("atomic-creator");
        payerId = insertUser("atomic-payer");
        settlementId = UUID.randomUUID();
        creatorParticipantId = UUID.randomUUID();
        UUID payerParticipantId = UUID.randomUUID();
        UUID expenseId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, allocation_type,
                            status, created_at, updated_at
                        ) VALUES (?, ?, '원자성 검증', 1000, 'ITEM_ALLOCATION',
                                  'REQUESTED', ?, ?)
                        """,
                settlementId,
                creatorId,
                NOW,
                NOW
        );
        insertParticipant(creatorParticipantId, creatorId, "생성자", 0);
        insertParticipant(payerParticipantId, payerId, "참여자", 1);
        jdbcTemplate.update("""
                        INSERT INTO settlement_expenses (
                            id, settlement_id, source, source_reference, description, amount,
                            display_order, created_at, updated_at
                        ) VALUES (?, ?, 'RECEIPT_OCR', 'atomic-receipt', '피자', 1000, 0, ?, ?)
                        """,
                expenseId,
                settlementId,
                NOW,
                NOW
        );
        jdbcTemplate.update("""
                        INSERT INTO settlement_items (
                            id, settlement_id, expense_id, name, quantity, amount,
                            display_order, created_at, updated_at
                        ) VALUES (?, ?, ?, '피자', 1, 1000, 0, ?, ?)
                        """,
                itemId,
                settlementId,
                expenseId,
                NOW,
                NOW
        );
        insertSelection(itemId, creatorParticipantId);
        insertSelection(itemId, payerParticipantId);
        jdbcTemplate.update("""
                        INSERT INTO settlement_item_submissions (
                            id, settlement_id, participant_id, submitted_at
                        ) VALUES (?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                settlementId,
                payerParticipantId,
                NOW
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE id = ?", settlementId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", creatorId, payerId);
    }

    @Test
    @DisplayName("Share 저장 실패 시 마지막 제출과 상태 전이를 모두 롤백한다")
    void itemAllocation_shareSaveFailure_rollsBackSubmissionAndStatus() {
        doThrow(new IllegalStateException("injected share failure"))
                .when(saveSettlementSharePort).saveAll(anyList());

        assertThatThrownBy(() -> writer.submit(
                settlementId,
                creatorParticipantId,
                NOW.plusMinutes(1),
                SettlementItemAllocationEntry.LINK
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("injected share failure");

        assertThat(count(
                "SELECT COUNT(*) FROM settlement_item_submissions WHERE settlement_id = ?",
                settlementId
        )).isOne();
        assertThat(count(
                "SELECT COUNT(*) FROM settlement_item_submissions WHERE settlement_id = ? AND participant_id = ?",
                settlementId,
                creatorParticipantId
        )).isZero();
        assertThat(count(
                "SELECT COUNT(*) FROM settlement_shares WHERE settlement_id = ?",
                settlementId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM settlements WHERE id = ?",
                String.class,
                settlementId
        )).isEqualTo("REQUESTED");
    }

    @Test
    @DisplayName("생성자만 제출하고 다른 참여자는 미제출이면 수동 중단한다")
    void itemAllocation_creatorOnlySubmission_allowsManualSuspend() {
        jdbcTemplate.update("DELETE FROM settlement_item_submissions WHERE settlement_id = ?", settlementId);
        writer.submit(settlementId, creatorParticipantId, NOW.plusMinutes(1),
                SettlementItemAllocationEntry.CREATOR);

        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId));

        assertThat(settlementService.suspend(settlementId).status()).isEqualTo(SettlementStatus.SUSPENDED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM settlements WHERE id = ?", String.class, settlementId
        )).isEqualTo("SUSPENDED");
        assertThat(count("SELECT COUNT(*) FROM settlement_item_submissions WHERE settlement_id = ?",
                settlementId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM settlement_item_selections WHERE settlement_id = ?",
                settlementId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM settlement_shares WHERE settlement_id = ?",
                settlementId)).isZero();
    }

    @Test
    @DisplayName("다른 참여자의 제출도 Share 존재 여부와 무관하게 수동 중단을 막는다")
    void itemAllocation_otherParticipantSubmission_blocksManualSuspend() {
        assertManualSuspendBlockedWithoutMutation();
    }

    @Test
    @DisplayName("품목 선택이 저장되어 있어도 제출 전이면 수동 중단하며 선택을 보존한다")
    void itemAllocation_selectionsWithoutSubmission_allowsManualSuspend() {
        jdbcTemplate.update("DELETE FROM settlement_item_submissions WHERE settlement_id = ?", settlementId);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId));

        assertThat(settlementService.suspend(settlementId).status()).isEqualTo(SettlementStatus.SUSPENDED);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM settlements WHERE id = ?", String.class, settlementId
        )).isEqualTo("SUSPENDED");
        assertThat(count("SELECT COUNT(*) FROM settlement_item_selections WHERE settlement_id = ?",
                settlementId)).isEqualTo(2);
    }

    private void assertManualSuspendBlockedWithoutMutation() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(creatorId));

        assertThatThrownBy(() -> settlementService.suspend(settlementId))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM settlements WHERE id = ?", String.class, settlementId
        )).isEqualTo("REQUESTED");
        assertThat(count("SELECT COUNT(*) FROM settlement_item_submissions WHERE settlement_id = ?",
                settlementId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM settlement_item_selections WHERE settlement_id = ?",
                settlementId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM settlement_shares WHERE settlement_id = ?",
                settlementId)).isZero();
    }

    private Long insertUser(String prefix) {
        String unique = UUID.randomUUID().toString();
        return jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        ) VALUES (?, '원자성 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                prefix + "-" + unique,
                prefix.substring(0, 3) + "_" + unique.replace("-", "").substring(0, 8),
                "test-" + unique.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
    }

    private void insertParticipant(UUID participantId, Long userId, String name, int order) {
        jdbcTemplate.update("""
                        INSERT INTO settlement_participants (
                            id, settlement_id, source, linked_user_id, phone_hash,
                            display_name, display_order, created_at, updated_at
                        ) VALUES (?, ?, 'REGISTERED_USER', ?, NULL, ?, ?, ?, ?)
                        """,
                participantId,
                settlementId,
                userId,
                name,
                order,
                NOW,
                NOW
        );
    }

    private void insertSelection(UUID itemId, UUID participantId) {
        jdbcTemplate.update("""
                        INSERT INTO settlement_item_selections (
                            id, settlement_id, item_id, participant_id, fraction,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 'HALF', ?, ?)
                        """,
                UUID.randomUUID(),
                settlementId,
                itemId,
                participantId,
                NOW,
                NOW
        );
    }

    private long count(String sql, Object... arguments) {
        return jdbcTemplate.queryForObject(sql, Long.class, arguments);
    }
}
