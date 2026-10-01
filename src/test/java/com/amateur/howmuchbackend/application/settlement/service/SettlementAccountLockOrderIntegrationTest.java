package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.adapter.out.persistence.external.CodefSecretCipher;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.DeleteAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementReceivingAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementReceivingAccountCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.infra.codef.config.CodefConfigurationFingerprint;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.codef.connected-id-encryption-key=aG93bXVjaC1jb2RlZi1tb2NrLWtleS0zMi1ieXRlcyE=",
        "howmuch.persistence.lock-timeout=5s"
})
@DisplayName("정산 계좌 잠금 순서 통합")
class SettlementAccountLockOrderIntegrationTest {
    private static final int ITERATIONS = 100;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 11, 0);
    private static final String SUCCESS = "SUCCESS";

    @Autowired private SelectSettlementReceivingAccountUseCase selectUseCase;
    @Autowired private DeleteAccountUseCase deleteUseCase;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CodefSecretCipher cipher;
    @Autowired private CodefConfigurationFingerprint configurationFingerprint;
    @MockitoBean private CurrentActorPort currentActorPort;

    private Long userId;
    private Long connectionId;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        userId = jdbcTemplate.queryForObject("""
                        INSERT INTO users (
                            identity_key, name, public_id, phone_number, pin_hash,
                            phone_verified, role, active, required_terms_agreed_at,
                            created_at, updated_at
                        ) VALUES (?, '잠금 순서 테스트', ?, ?, 'not-used', TRUE, 'USER', TRUE, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                "lock-order-" + unique,
                "lock_" + unique.replace("-", "").substring(0, 8),
                "test-" + unique.substring(0, 12),
                NOW,
                NOW,
                NOW
        );
        connectionId = jdbcTemplate.queryForObject("""
                        INSERT INTO external_connections (
                            user_id, connected_id_ciphertext, configuration_fingerprint,
                            active, created_at, updated_at
                        ) VALUES (?, ?, ?, TRUE, ?, ?)
                        RETURNING id
                        """,
                Long.class,
                userId,
                cipher.encryptConnectedId(userId, "CID-LOCK-ORDER"),
                configurationFingerprint.current(),
                NOW,
                NOW
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
    @DisplayName("수령계좌 선택과 계좌 삭제를 100회 동시에 실행해 데드락 없이 직렬화한다")
    void accountLock_selectAndDelete_noDeadlock() throws Exception {
        long deadlocksBefore = deadlockCount();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            for (int iteration = 1; iteration <= ITERATIONS; iteration++) {
                Long accountId = insertAccount(iteration);
                UUID settlementId = insertDraftSettlement(iteration);
                Outcome outcome = runConcurrently(executor, settlementId, accountId);

                boolean selectionWon = SUCCESS.equals(outcome.selection())
                        && AccountStatusCode.ACCOUNT_IN_USE_BY_SETTLEMENT.getCode()
                        .equals(outcome.deletion());
                boolean deletionWon = SUCCESS.equals(outcome.deletion())
                        && AccountStatusCode.ACCOUNT_NOT_FOUND.getCode()
                        .equals(outcome.selection());
                assertThat(selectionWon || deletionWon)
                        .as("iteration %s outcome=%s", iteration, outcome)
                        .isTrue();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(deadlockCount() - deadlocksBefore).isZero();
    }

    private Outcome runConcurrently(
            ExecutorService executor,
            UUID settlementId,
            Long accountId
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> selection = () -> execute(ready, start, () ->
                selectUseCase.select(
                        settlementId,
                        new SelectSettlementReceivingAccountCommand(accountId)
                )
        );
        Callable<String> deletion = () -> execute(ready, start, () ->
                deleteUseCase.delete(accountId)
        );
        Future<String> selectionResult = executor.submit(selection);
        Future<String> deletionResult = executor.submit(deletion);
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        return new Outcome(
                selectionResult.get(10, TimeUnit.SECONDS),
                deletionResult.get(10, TimeUnit.SECONDS)
        );
    }

    private String execute(
            CountDownLatch ready,
            CountDownLatch start,
            ThrowingOperation operation
    ) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        try {
            operation.run();
            return SUCCESS;
        } catch (ApplicationException exception) {
            return exception.getStatusCode().getCode();
        }
    }

    private Long insertAccount(int iteration) {
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT nextval(pg_get_serial_sequence('accounts', 'id'))",
                Long.class
        );
        jdbcTemplate.update("""
                        INSERT INTO accounts (
                            id, user_id, external_connection_id, organization, candidate_key,
                            external_account_key_ciphertext, type, name, masked_number, balance,
                            currency, active, product_code, is_default, display_order,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, '0020', ?, ?, 'DEPOSIT_TRUST', '잠금 계좌',
                                  '****6789', 1000000, 'KRW', TRUE, '11', FALSE, ?, ?, ?)
                        """,
                accountId,
                userId,
                connectionId,
                "lock-candidate-" + iteration + "-" + UUID.randomUUID(),
                cipher.encryptAccountKey(userId, connectionId, "1002" + accountId),
                iteration,
                NOW,
                NOW
        );
        return accountId;
    }

    private UUID insertDraftSettlement(int iteration) {
        UUID settlementId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO settlements (
                            id, creator_id, title, total_amount, status,
                            created_at, updated_at
                        ) VALUES (?, ?, ?, 10000, 'DRAFT', ?, ?)
                        """,
                settlementId,
                userId,
                "잠금 순서 " + iteration,
                NOW,
                NOW
        );
        return settlementId;
    }

    private long deadlockCount() {
        return jdbcTemplate.queryForObject("""
                        SELECT deadlocks
                          FROM pg_stat_database
                         WHERE datname = current_database()
                        """,
                Long.class
        );
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run();
    }

    private record Outcome(String selection, String deletion) {
    }
}
