package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.DeleteAccountUseCase;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=aG93bXVjaC1jb2RlZi1tb2NrLWtleS0zMi1ieXRlcyE="
})
@DisplayName("계좌 삭제 PostgreSQL 통합")
class AccountDeletionPersistenceIntegrationTest {
    @Autowired private DeleteAccountUseCase deleteAccountUseCase;
    @Autowired private SaveAccountPort saveAccountPort;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private CodefConfigurationFingerprint configurationFingerprint;
    @MockitoBean private CurrentActorPort currentActorPort;

    private Long userId;
    private Long connectionId;
    private Long deletedAccountId;
    private Long nextAccountId;
    private UUID settlementId;
    private String deletedCandidateKey;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 8, 24, 6, 0);
        userId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                            role, active, required_terms_agreed_at, created_at, updated_at
                        ) VALUES (?, '삭제 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "account-deletion-" + unique,
                "delete_" + unique.replace("-", "").substring(0, 12),
                "test-" + unique.substring(0, 12),
                now,
                now,
                now
        );
        connectionId = jdbcTemplate.queryForObject("""
                        INSERT INTO external_connections (
                            user_id, connected_id_ciphertext, configuration_fingerprint, active,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, TRUE, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                cipher.encryptConnectedId(userId, "CID-DELETE"),
                configurationFingerprint.current(),
                now,
                now
        );
        deletedCandidateKey = "candidate-delete-" + unique;
        deletedAccountId = insertAccount(deletedCandidateKey, true, 1, now);
        nextAccountId = insertAccount("candidate-next-" + unique, false, 0, now);
        settlementId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, receiving_account_id,
                            status, created_at, updated_at
                        ) VALUES (?, ?, '삭제 계좌 정산', 10000, ?, 'COMPLETED', ?, ?)
                        """,
                settlementId,
                userId,
                deletedAccountId,
                now,
                now
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM settlements WHERE creator_id = ?", userId);
        jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM account_records WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("삭제 기록을 보존하고 같은 후보를 새 Account ID로 다시 연결한다")
    void accountDeletion_recordPreserved_reconnectsWithNewId() {
        deleteAccountUseCase.delete(deletedAccountId);

        Account reconnected = saveAccountPort.saveAll(List.of(new Account(
                null,
                userId,
                connectionId,
                "0020",
                deletedCandidateKey,
                "1002999999999",
                AccountType.DEPOSIT_TRUST,
                "재연결 통장",
                "****9999",
                new BigDecimal("500000"),
                "KRW",
                true,
                "11",
                false,
                2
        ))).getFirst();
        LocalDateTime fixtureLinkedAt = LocalDateTime.of(2000, 1, 1, 0, 0);
        jdbcTemplate.update("UPDATE accounts SET created_at = ?, updated_at = ? WHERE id = ?",
                fixtureLinkedAt, fixtureLinkedAt, reconnected.id());

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = ?", deletedAccountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM account_records WHERE id = ? AND deleted_at IS NOT NULL",
                deletedAccountId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = ? AND is_default = TRUE", nextAccountId)).isOne();
        assertThat(count("SELECT COUNT(*) FROM settlements WHERE id = ? AND receiving_account_id IS NULL",
                settlementId)).isOne();
        assertThat(reconnected.id()).isNotEqualTo(deletedAccountId);
        assertThat(count("SELECT COUNT(*) FROM account_records WHERE id = ? AND deleted_at IS NULL",
                reconnected.id())).isOne();
    }

    @ParameterizedTest
    @EnumSource(value = SettlementStatus.class, names = {"DRAFT", "REQUESTED", "AWAITING_DEPOSIT", "SUSPENDED"})
    @DisplayName("진행 중 정산이 수령 계좌로 사용하면 계좌 삭제를 거부한다")
    void accountDeletion_inProgressSettlement_reference_rejected(SettlementStatus status) {
        jdbcTemplate.update("UPDATE settlements SET status = ? WHERE id = ?", status.name(), settlementId);

        assertThatThrownBy(() -> deleteAccountUseCase.delete(deletedAccountId))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(AccountStatusCode.ACCOUNT_IN_USE_BY_SETTLEMENT));

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = ?", deletedAccountId)).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT receiving_account_id FROM settlements WHERE id = ?",
                Long.class,
                settlementId
        )).isEqualTo(deletedAccountId);
    }

    @Test
    @DisplayName("논리 삭제된 SUSPENDED 정산은 계좌 삭제를 막지 않는다")
    void accountDeletion_softDeletedSuspendedSettlement_allowed() {
        jdbcTemplate.update("UPDATE settlements SET status = 'SUSPENDED', deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 8, 24, 7, 0), settlementId);

        deleteAccountUseCase.delete(deletedAccountId);

        assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = ?", deletedAccountId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM settlements WHERE id = ? AND deleted_at IS NOT NULL",
                settlementId)).isOne();
    }

    private Long insertAccount(String candidateKey, boolean isDefault, int displayOrder, LocalDateTime now) {
        Long temporaryId = jdbcTemplate.queryForObject("SELECT nextval(pg_get_serial_sequence('accounts', 'id'))", Long.class);
        return jdbcTemplate.queryForObject("""
                        INSERT INTO accounts (
                            id, user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number, balance,
                            currency, active, product_code, is_default, display_order, created_at, updated_at
                        ) VALUES (?, ?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '생활비 통장',
                                  '****6789', 1000000, 'KRW', TRUE, '11', ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                temporaryId,
                userId,
                connectionId,
                candidateKey,
                cipher.encryptAccountKey(userId, connectionId, "1002" + temporaryId),
                isDefault,
                displayOrder,
                now,
                now
        );
    }

    private long count(String sql, Object value) {
        return jdbcTemplate.queryForObject(sql, Long.class, value);
    }
}
