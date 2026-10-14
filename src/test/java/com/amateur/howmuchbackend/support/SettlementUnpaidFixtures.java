package com.amateur.howmuchbackend.support;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 영속 조회와 실제 HTTP 검증에서 생성한 데이터만 정리한다. */
public final class SettlementUnpaidFixtures implements AutoCloseable {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 0, 0);
    private final JdbcTemplate jdbc;
    private final List<Long> userIds = new ArrayList<>();
    private final List<UUID> settlementIds = new ArrayList<>();
    private final List<Long> transactionIds = new ArrayList<>();
    private final Map<Long, Long> accountIds = new HashMap<>();
    private final Map<UUID, Integer> displayOrders = new HashMap<>();

    public SettlementUnpaidFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long user() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Long id = jdbc.queryForObject("""
                INSERT INTO users (
                    identity_key, name, public_id, phone_number, pin_hash,
                    phone_verified, role, active, created_at, updated_at
                ) VALUES (?, '집계 검증', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?)
                RETURNING id
                """, Long.class, "unpaid-" + suffix, "unpaid_" + suffix.substring(0, 12),
                "test-" + suffix.substring(0, 12), NOW, NOW);
        userIds.add(id);
        return id;
    }

    public UUID settlement(Long creatorId, SettlementStatus status, SettlementAllocationType allocationType) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlements (
                    id, creator_id, title, total_amount, allocation_type, status, created_at, updated_at
                ) VALUES (?, ?, '미납 집계 검증', 100000, ?, ?, ?, ?)
                """, id, creatorId, allocationType.name(), status.name(), NOW, NOW);
        settlementIds.add(id);
        return id;
    }

    public void markDeleted(UUID settlementId) {
        jdbc.update("UPDATE settlements SET deleted_at = ? WHERE id = ?", NOW, settlementId);
    }

    public UUID participant(UUID settlementId, Long linkedUserId) {
        return participant(settlementId, linkedUserId, null);
    }

    public UUID participant(UUID settlementId, Long linkedUserId, String phoneHash) {
        UUID id = UUID.randomUUID();
        int order = displayOrders.merge(settlementId, 1, Integer::sum) - 1;
        jdbc.update("""
                INSERT INTO settlement_participants (
                    id, settlement_id, source, linked_user_id, phone_hash,
                    display_name, display_order, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, '같은 이름', ?, ?, ?)
                """, id, settlementId, linkedUserId == null ? "CONTACT" : "REGISTERED_USER",
                linkedUserId, phoneHash, order, NOW, NOW);
        return id;
    }

    public UUID share(UUID settlementId, UUID participantId, long amount) {
        Long creatorId = jdbc.queryForObject(
                "SELECT creator_id FROM settlements WHERE id = ?", Long.class, settlementId
        );
        receivingAccount(creatorId, settlementId);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO settlement_shares (id, settlement_id, payer_participant_id, amount, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, settlementId, participantId, amount, NOW);
        return id;
    }

    public void submitZero(UUID settlementId, UUID participantId) {
        jdbc.update("""
                INSERT INTO settlement_item_submissions (id, settlement_id, participant_id, submitted_at)
                VALUES (?, ?, ?, ?)
                """, UUID.randomUUID(), settlementId, participantId, NOW);
    }

    public void deposit(Long creatorId, UUID settlementId, UUID shareId, long amount) {
        deposit(creatorId, settlementId, shareId, BigDecimal.valueOf(amount));
    }

    public void deposit(Long creatorId, UUID settlementId, UUID shareId, BigDecimal amount) {
        Long accountId = receivingAccount(creatorId, settlementId);
        Long transactionId = transaction(creatorId, amount);
        jdbc.update("""
                INSERT INTO settlement_deposits (
                    id, settlement_id, share_id, source_account_id, account_transaction_id, matched_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), settlementId, shareId, accountId, transactionId, NOW);
    }

    private Long receivingAccount(Long creatorId, UUID settlementId) {
        Long accountId = accountIds.computeIfAbsent(creatorId, this::accountRecord);
        jdbc.update("""
                INSERT INTO settlement_receiving_account_snapshots (
                    settlement_id, source_account_id, organization,
                    account_number_ciphertext, account_holder, created_at
                ) VALUES (?, ?, '0020', 'synthetic-unused-account', '집계 검증', ?)
                ON CONFLICT (settlement_id) DO NOTHING
                """, settlementId, accountId, NOW);
        return accountId;
    }

    public Long transaction(Long creatorId, BigDecimal amount) {
        Long accountId = accountIds.computeIfAbsent(creatorId, this::accountRecord);
        Long id = jdbc.queryForObject("""
                INSERT INTO account_transactions (
                    account_id, external_transaction_key, transaction_type, amount,
                    transacted_at, description, after_balance, created_at, updated_at
                ) VALUES (?, ?, 'INCOME', ?, ?, '집계 검증 입금', 100000, ?, ?)
                RETURNING id
                """, Long.class, accountId, UUID.randomUUID().toString().replace("-", "").repeat(2),
                amount, NOW, NOW, NOW);
        transactionIds.add(id);
        return id;
    }

    private Long accountRecord(Long creatorId) {
        Long id = Math.max(1L, UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        jdbc.update("""
                INSERT INTO account_records (
                    id, user_id, organization, type, name, masked_number, currency, linked_at
                ) VALUES (?, ?, '0020', 'DEPOSIT_TRUST', '집계 검증 계좌', '****0000', 'KRW', ?)
                """, id, creatorId, NOW);
        return id;
    }

    @Override
    public void close() {
        boolean immutableDeposits = SettlementDepositTestCleanup.immutableDepositsEnabled(jdbc);
        if (!immutableDeposits) {
            settlementIds.forEach(id -> SettlementDepositTestCleanup.deleteDepositsWhenMutable(jdbc, id));
        }
        transactionIds.forEach(id -> jdbc.update("""
                DELETE FROM account_transactions tx
                WHERE tx.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM settlement_deposits deposit
                      WHERE deposit.account_transaction_id = tx.id
                  )
                """, id));
        settlementIds.forEach(id -> {
            if (!immutableDeposits || !SettlementDepositTestCleanup.hasDepositForSettlement(jdbc, id)) {
                jdbc.update("DELETE FROM settlements WHERE id = ?", id);
            }
        });
        accountIds.values().forEach(id -> jdbc.update("""
                DELETE FROM account_records account_record
                WHERE account_record.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM settlement_deposits deposit
                      WHERE deposit.source_account_id = account_record.id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM settlement_receiving_account_snapshots snapshot
                      WHERE snapshot.source_account_id = account_record.id
                  )
                """, id));
        userIds.forEach(id -> {
            jdbc.update("DELETE FROM auth_audit_logs WHERE user_id = ?", id);
            jdbc.update("""
                    DELETE FROM users user_record
                    WHERE user_record.id = ?
                      AND NOT EXISTS (SELECT 1 FROM account_records WHERE user_id = user_record.id)
                      AND NOT EXISTS (SELECT 1 FROM settlements WHERE creator_id = user_record.id)
                      AND NOT EXISTS (SELECT 1 FROM settlement_participants WHERE linked_user_id = user_record.id)
                    """, id);
        });
    }
}
