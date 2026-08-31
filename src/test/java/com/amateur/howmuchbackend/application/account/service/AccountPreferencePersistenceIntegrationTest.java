package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.SetDefaultAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.account.UpdateAccountDisplayOrderUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.UpdateAccountDisplayOrderCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=aG93bXVjaC1jb2RlZi1tb2NrLWtleS0zMi1ieXRlcyE="
})
class AccountPreferencePersistenceIntegrationTest {
    @Autowired private SetDefaultAccountUseCase setDefaultAccountUseCase;
    @Autowired private UpdateAccountDisplayOrderUseCase updateAccountDisplayOrderUseCase;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private CodefConfigurationFingerprint configurationFingerprint;
    @MockitoBean private CurrentActorPort currentActorPort;

    private Long userId;
    private Long connectionId;
    private Long firstAccountId;
    private Long secondAccountId;
    private Long thirdAccountId;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.of(2026, 8, 31, 12, 0);
        userId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash, phone_verified,
                            role, active, required_terms_agreed_at, created_at, updated_at
                        ) VALUES (?, '계좌 설정 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "account-preference-" + unique,
                "pref_" + unique.replace("-", "").substring(0, 12),
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
                cipher.encryptConnectedId(userId, "CID-PREFERENCE"),
                configurationFingerprint.current(),
                now,
                now
        );
        firstAccountId = insertAccount("first-" + unique, false, 0, now);
        secondAccountId = insertAccount("second-" + unique, true, 1, now);
        thirdAccountId = insertAccount("third-" + unique, false, 2, now);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM account_records WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM external_connections WHERE id = ?", connectionId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void changesDefaultAndMovesAccountToRequestedPosition() {
        setDefaultAccountUseCase.setDefault(firstAccountId);
        updateAccountDisplayOrderUseCase.updateDisplayOrder(
                new UpdateAccountDisplayOrderCommand(thirdAccountId, 0)
        );

        assertThat(jdbcTemplate.queryForList("""
                        SELECT id
                          FROM accounts
                         WHERE user_id = ?
                           AND active = TRUE
                           AND is_default = TRUE
                        """, Long.class, userId))
                .containsExactly(firstAccountId);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT id
                          FROM accounts
                         WHERE user_id = ?
                           AND active = TRUE
                         ORDER BY display_order, id
                        """, Long.class, userId))
                .containsExactly(thirdAccountId, firstAccountId, secondAccountId);
        assertThat(jdbcTemplate.queryForList("""
                        SELECT display_order
                          FROM accounts
                         WHERE user_id = ?
                           AND active = TRUE
                         ORDER BY display_order
                        """, Integer.class, userId))
                .containsExactly(0, 1, 2);
    }

    private Long insertAccount(
            String candidateKey,
            boolean isDefault,
            int displayOrder,
            LocalDateTime now
    ) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO accounts (
                            user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number, balance,
                            currency, active, product_code, is_default, display_order,
                            created_at, updated_at
                        ) VALUES (?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '생활비 통장',
                                  '****6789', 1000000, 'KRW', TRUE, '11', ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                connectionId,
                candidateKey,
                cipher.encryptAccountKey(userId, connectionId, "1002" + displayOrder),
                isDefault,
                displayOrder,
                now,
                now
        );
    }
}
