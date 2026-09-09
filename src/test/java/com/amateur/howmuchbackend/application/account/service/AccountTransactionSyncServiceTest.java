package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.RequestAccountTransactionSyncCommand;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncEligibilityPort;
import com.amateur.howmuchbackend.application.port.out.account.AccountTransactionSyncJobStorePort;
import com.amateur.howmuchbackend.application.port.out.account.FinanceSyncReferenceStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.external.FinanceConnectorRegistrationPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncJob;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncStatus;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 거래 동기화 Application 흐름")
class AccountTransactionSyncServiceTest {
    private static final Long USER_ID = 1L;
    private static final Long CONNECTION_ID = 10L;
    private static final Long ACCOUNT_ID = 100L;
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CONNECTION_REF = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_REF = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID SYNC_JOB_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-09T03:00:00Z");

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private AccountTransactionSyncEligibilityPort eligibilityPort;
    @Mock private FinanceSyncReferenceStorePort referenceStorePort;
    @Mock private FinanceConnectorRegistrationPort connectorRegistrationPort;
    @Mock private AccountTransactionSyncJobStorePort jobStorePort;

    private AccountTransactionSyncService service;

    @BeforeEach
    void setUp() {
        service = new AccountTransactionSyncService(
                currentActorPort,
                loadAccountPort,
                connectionStorePort,
                eligibilityPort,
                referenceStorePort,
                connectorRegistrationPort,
                jobStorePort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        lenient().when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
    }

    @Test
    @DisplayName("Connector 등록을 transaction 밖에서 수행하고 job을 접수한다")
    void registersThenEnqueuesOutsideTransaction() {
        RequestAccountTransactionSyncCommand command = command(IDEMPOTENCY_KEY, "2026-08-01", "2026-08-31");
        Account account = account(ACCOUNT_ID, "0020", "10020001", "KRW", "11");
        Account second = account(ACCOUNT_ID + 1, "0020", "10020002", "USD", "11");
        when(jobStorePort.findByIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(eligibilityPort.isEnabled(ACCOUNT_ID)).thenReturn(true);
        when(connectionStorePort.findActiveByIdAndUserId(CONNECTION_ID, USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(CONNECTION_ID, USER_ID, "connected-id")));
        when(loadAccountPort.findActiveByConnectionAndOrganization(CONNECTION_ID, USER_ID, "0020"))
                .thenReturn(List.of(account, second));
        when(referenceStorePort.prepare(CONNECTION_ID, List.of(ACCOUNT_ID, ACCOUNT_ID + 1)))
                .thenReturn(new FinanceSyncReferenceStorePort.FinanceSyncReferences(
                        CONNECTION_REF,
                        Map.of(ACCOUNT_ID, ACCOUNT_REF, ACCOUNT_ID + 1, UUID.randomUUID())
                ));
        when(connectorRegistrationPort.register(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new FinanceConnectorRegistrationPort.RegistrationResult(3);
        });
        AccountTransactionSyncJob job = job(command, "a".repeat(64));
        when(jobStorePort.enqueue(any())).thenReturn(new AccountTransactionSyncJobStorePort.EnqueueResult(
                AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.CREATED,
                job
        ));

        var result = service.request(command);

        assertThat(result.syncJobId()).isEqualTo(SYNC_JOB_ID);
        ArgumentCaptor<FinanceConnectorRegistrationPort.Registration> registration =
                ArgumentCaptor.forClass(FinanceConnectorRegistrationPort.Registration.class);
        verify(connectorRegistrationPort).register(registration.capture());
        assertThat(registration.getValue().accounts())
                .extracting(FinanceConnectorRegistrationPort.Account::providerAccountRef)
                .containsExactly("10020001", "10020002");
        ArgumentCaptor<AccountTransactionSyncJobStorePort.EnqueueCommand> enqueue =
                ArgumentCaptor.forClass(AccountTransactionSyncJobStorePort.EnqueueCommand.class);
        verify(jobStorePort).enqueue(enqueue.capture());
        assertThat(enqueue.getValue().connectionVersion()).isEqualTo(3);
        assertThat(enqueue.getValue().requestHash()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("같은 멱등성 Key와 같은 요청은 기존 job을 반환한다")
    void returnsExistingIdempotentJob() {
        RequestAccountTransactionSyncCommand command = command(IDEMPOTENCY_KEY, "2026-08-01", "2026-08-31");
        AccountTransactionSyncJob existing = job(command, requestHash(command));
        when(jobStorePort.findByIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));

        assertThat(service.request(command).syncJobId()).isEqualTo(SYNC_JOB_ID);

        verifyNoInteractions(loadAccountPort, connectorRegistrationPort, referenceStorePort);
    }

    @Test
    @DisplayName("같은 멱등성 Key의 다른 요청은 충돌시킨다")
    void rejectsIdempotencyConflict() {
        RequestAccountTransactionSyncCommand command = command(IDEMPOTENCY_KEY, "2026-08-01", "2026-08-31");
        when(jobStorePort.findByIdempotencyKey(USER_ID, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(job(command, "f".repeat(64))));

        assertStatus(
                () -> service.request(command),
                AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_IDEMPOTENCY_CONFLICT
        );
        verifyNoInteractions(loadAccountPort, connectorRegistrationPort, referenceStorePort);
    }

    @Test
    @DisplayName("31일을 초과하거나 미래를 포함한 날짜 범위를 거절한다")
    void rejectsInvalidDateRange() {
        assertStatus(
                () -> service.request(command(IDEMPOTENCY_KEY, "2026-08-01", "2026-09-01")),
                AccountStatusCode.INVALID_ACCOUNT_TRANSACTION_SYNC_REQUEST
        );
        assertStatus(
                () -> service.request(command(IDEMPOTENCY_KEY, "2026-09-09", "2026-09-10")),
                AccountStatusCode.INVALID_ACCOUNT_TRANSACTION_SYNC_REQUEST
        );
        verify(currentActorPort, never()).getCurrentActor();
    }

    @Test
    @DisplayName("비허용 계좌와 이미 실행 중인 계좌를 명시적인 충돌로 거절한다")
    void rejectsDisabledAndActiveAccount() {
        RequestAccountTransactionSyncCommand disabled = command(IDEMPOTENCY_KEY, "2026-09-01", "2026-09-09");
        Account account = account(ACCOUNT_ID, "0020", "10020001", "KRW", "11");
        when(jobStorePort.findByIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(eligibilityPort.isEnabled(ACCOUNT_ID)).thenReturn(false, true);

        assertStatus(
                () -> service.request(disabled),
                AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_NOT_ENABLED
        );

        when(connectionStorePort.findActiveByIdAndUserId(CONNECTION_ID, USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(CONNECTION_ID, USER_ID, "connected-id")));
        when(loadAccountPort.findActiveByConnectionAndOrganization(CONNECTION_ID, USER_ID, "0020"))
                .thenReturn(List.of(account));
        when(referenceStorePort.prepare(CONNECTION_ID, List.of(ACCOUNT_ID)))
                .thenReturn(new FinanceSyncReferenceStorePort.FinanceSyncReferences(
                        CONNECTION_REF, Map.of(ACCOUNT_ID, ACCOUNT_REF)
                ));
        when(connectorRegistrationPort.register(any()))
                .thenReturn(new FinanceConnectorRegistrationPort.RegistrationResult(1));
        when(jobStorePort.enqueue(any())).thenReturn(new AccountTransactionSyncJobStorePort.EnqueueResult(
                AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.ACTIVE_ACCOUNT_CONFLICT,
                null
        ));

        assertStatus(
                () -> service.request(disabled),
                AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING
        );
    }

    @Test
    @DisplayName("이미 활성 작업이 있으면 Connector 등록 전에 거절한다")
    void rejectsExistingActiveJobBeforeConnectorRegistration() {
        RequestAccountTransactionSyncCommand command = command(
                IDEMPOTENCY_KEY, "2026-09-01", "2026-09-09"
        );
        Account account = account(ACCOUNT_ID, "0020", "10020001", "KRW", "11");
        when(jobStorePort.findByIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(eligibilityPort.isEnabled(ACCOUNT_ID)).thenReturn(true);
        when(jobStorePort.hasActiveJob(ACCOUNT_ID)).thenReturn(true);

        assertStatus(
                () -> service.request(command),
                AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_ALREADY_RUNNING
        );
        verifyNoInteractions(connectorRegistrationPort, referenceStorePort);
    }

    @Test
    @DisplayName("Connector 필수 정보가 없는 형제 계좌는 등록 snapshot에서 제외한다")
    void excludesIncompleteSiblingAccountFromRegistration() {
        RequestAccountTransactionSyncCommand command = command(
                IDEMPOTENCY_KEY, "2026-09-01", "2026-09-09"
        );
        Account account = account(ACCOUNT_ID, "0020", "10020001", "krw", "11");
        Account incomplete = account(ACCOUNT_ID + 1, "0020", "10020002", "US$", "11");
        when(jobStorePort.findByIdempotencyKey(USER_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(eligibilityPort.isEnabled(ACCOUNT_ID)).thenReturn(true);
        when(connectionStorePort.findActiveByIdAndUserId(CONNECTION_ID, USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(CONNECTION_ID, USER_ID, "connected-id")));
        when(loadAccountPort.findActiveByConnectionAndOrganization(CONNECTION_ID, USER_ID, "0020"))
                .thenReturn(List.of(account, incomplete));
        when(referenceStorePort.prepare(CONNECTION_ID, List.of(ACCOUNT_ID)))
                .thenReturn(new FinanceSyncReferenceStorePort.FinanceSyncReferences(
                        CONNECTION_REF, Map.of(ACCOUNT_ID, ACCOUNT_REF)
                ));
        when(connectorRegistrationPort.register(any()))
                .thenReturn(new FinanceConnectorRegistrationPort.RegistrationResult(1));
        when(jobStorePort.enqueue(any())).thenReturn(new AccountTransactionSyncJobStorePort.EnqueueResult(
                AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.CREATED,
                job(command, requestHash(command))
        ));

        service.request(command);

        ArgumentCaptor<FinanceConnectorRegistrationPort.Registration> registration =
                ArgumentCaptor.forClass(FinanceConnectorRegistrationPort.Registration.class);
        verify(connectorRegistrationPort).register(registration.capture());
        assertThat(registration.getValue().accounts()).singleElement().satisfies(registered -> {
            assertThat(registered.accountRef()).isEqualTo(ACCOUNT_REF);
            assertThat(registered.currency()).isEqualTo("KRW");
        });
    }

    @Test
    @DisplayName("존재하지 않거나 지원하지 않는 계좌를 Connector 호출 전에 거절한다")
    void rejectsMissingAndUnsupportedAccount() {
        UUID missingKey = UUID.randomUUID();
        UUID unsupportedKey = UUID.randomUUID();
        when(jobStorePort.findByIdempotencyKey(USER_ID, missingKey)).thenReturn(Optional.empty());
        when(jobStorePort.findByIdempotencyKey(USER_ID, unsupportedKey)).thenReturn(Optional.empty());
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID))
                .thenReturn(Optional.empty(), Optional.of(account(
                        ACCOUNT_ID, "0020", "10020001", "USD", "11"
                )));
        when(eligibilityPort.isEnabled(ACCOUNT_ID)).thenReturn(true);

        assertStatus(
                () -> service.request(command(missingKey, "2026-09-01", "2026-09-09")),
                AccountStatusCode.ACCOUNT_NOT_FOUND
        );
        assertStatus(
                () -> service.request(command(unsupportedKey, "2026-09-01", "2026-09-09")),
                AccountStatusCode.ACCOUNT_TRANSACTION_NOT_SUPPORTED
        );
        verifyNoInteractions(connectorRegistrationPort, referenceStorePort);
    }

    @Test
    @DisplayName("접수 직전 계좌 소실과 오래된 Connector 등록을 안전한 오류로 변환한다")
    void mapsAtomicEnqueueGuardOutcomes() {
        UUID unavailableKey = UUID.randomUUID();
        UUID staleKey = UUID.randomUUID();
        Account account = account(ACCOUNT_ID, "0020", "10020001", "KRW", "11");
        when(jobStorePort.findByIdempotencyKey(USER_ID, unavailableKey)).thenReturn(Optional.empty());
        when(jobStorePort.findByIdempotencyKey(USER_ID, staleKey)).thenReturn(Optional.empty());
        when(loadAccountPort.findActiveByIdAndUserId(ACCOUNT_ID, USER_ID)).thenReturn(Optional.of(account));
        when(eligibilityPort.isEnabled(ACCOUNT_ID)).thenReturn(true);
        when(connectionStorePort.findActiveByIdAndUserId(CONNECTION_ID, USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(CONNECTION_ID, USER_ID, "connected-id")));
        when(loadAccountPort.findActiveByConnectionAndOrganization(CONNECTION_ID, USER_ID, "0020"))
                .thenReturn(List.of(account));
        when(referenceStorePort.prepare(CONNECTION_ID, List.of(ACCOUNT_ID)))
                .thenReturn(new FinanceSyncReferenceStorePort.FinanceSyncReferences(
                        CONNECTION_REF, Map.of(ACCOUNT_ID, ACCOUNT_REF)
                ));
        when(connectorRegistrationPort.register(any()))
                .thenReturn(new FinanceConnectorRegistrationPort.RegistrationResult(1));
        when(jobStorePort.enqueue(any()))
                .thenReturn(new AccountTransactionSyncJobStorePort.EnqueueResult(
                        AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.ACCOUNT_UNAVAILABLE,
                        null
                ))
                .thenReturn(new AccountTransactionSyncJobStorePort.EnqueueResult(
                        AccountTransactionSyncJobStorePort.EnqueueResult.Outcome.STALE_REGISTRATION,
                        null
                ));

        assertStatus(
                () -> service.request(command(unavailableKey, "2026-09-01", "2026-09-09")),
                AccountStatusCode.ACCOUNT_NOT_FOUND
        );
        assertStatus(
                () -> service.request(command(staleKey, "2026-09-01", "2026-09-09")),
                AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_CONNECTOR_UNAVAILABLE
        );
    }

    @Test
    @DisplayName("현재 사용자 소유 job만 조회한다")
    void getsOnlyOwnedJob() {
        when(jobStorePort.findByIdAndUserId(SYNC_JOB_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertStatus(
                () -> service.get(SYNC_JOB_ID),
                AccountStatusCode.ACCOUNT_TRANSACTION_SYNC_JOB_NOT_FOUND
        );
    }

    private RequestAccountTransactionSyncCommand command(UUID key, String from, String to) {
        return new RequestAccountTransactionSyncCommand(
                ACCOUNT_ID, key, LocalDate.parse(from), LocalDate.parse(to)
        );
    }

    private Account account(Long id, String organization, String externalKey, String currency, String productCode) {
        return new Account(
                id, USER_ID, CONNECTION_ID, organization, "candidate-" + id, externalKey,
                AccountType.DEPOSIT_TRUST, "계좌", "****0001", BigDecimal.ZERO,
                currency, true, productCode, false, 0
        );
    }

    private AccountTransactionSyncJob job(RequestAccountTransactionSyncCommand command, String hash) {
        return new AccountTransactionSyncJob(
                SYNC_JOB_ID, USER_ID, ACCOUNT_ID, command.idempotencyKey(), hash,
                command.fromDate(), command.toDate(), AccountTransactionSyncStatus.QUEUED,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null, null, null, null
        );
    }

    private String requestHash(RequestAccountTransactionSyncCommand command) {
        try {
            String value = command.accountId() + "|" + command.fromDate() + "|" + command.toDate();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void assertStatus(Runnable action, AccountStatusCode statusCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(ApplicationException.class)
                .satisfies(exception -> assertThat(((ApplicationException) exception).getStatusCode())
                        .isEqualTo(statusCode));
    }
}
