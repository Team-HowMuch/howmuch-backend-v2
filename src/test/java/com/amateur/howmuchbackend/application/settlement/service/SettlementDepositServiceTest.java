package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.settlement.netting.service.NettingEstimateInvalidationService;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.service.AccountCredentialProtectionService;
import com.amateur.howmuchbackend.application.notification.service.BusinessNotificationService;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementDepositMatchingPolicy;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementDeposit;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositReconciliationJobStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementBankLookupBudgetPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementTransferReconciliationPolicy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 입금 대조 Application 흐름")
class SettlementDepositServiceTest {
    @org.mockito.Mock private NettingEstimateInvalidationService estimateInvalidation;
    private static final Long CREATOR_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID OTHER_SETTLEMENT_ID = UUID.fromString("56d2237c-714e-46b0-a036-e5f44f8f509a");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("bb97ee76-3a82-4ccc-b324-4a9974c8e1af");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final UUID CREATOR_SHARE_ID = UUID.fromString("9de946eb-bf2e-4fbb-b5e2-61d76098669c");
    private static final UUID PAYER_SHARE_ID = UUID.fromString("0d90a9cd-c9d6-49d0-9217-4c105b9fb3da");
    private static final UUID OTHER_SHARE_ID = UUID.fromString("14bdff25-6157-4fac-bbf5-cb4605704aae");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 20, 0, 0);

    @Mock private BusinessNotificationService notifications;
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    @Mock private SettlementDepositStorePort depositStorePort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private LoadAccountTransactionPort loadTransactionPort;
    @Mock private SaveAccountTransactionPort saveTransactionPort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private BankTransactionPort bankTransactionPort;
    @Mock private BankCatalog bankCatalog;
    @Mock private AccountCredentialProtectionService credentialProtection;

    private SettlementDepositService service;

    @BeforeEach
    void setUp() {
        SettlementDepositReader reader = new SettlementDepositReader(
                loadSettlementPort,
                loadSettlementSharePort,
                loadSettlementParticipantPort,
                loadSnapshotPort,
                loadTransactionPort,
                depositStorePort
        );
        SettlementDepositWriter writer = new SettlementDepositWriter(
                loadAccountPort,
                loadSettlementPort,
                saveTransactionPort,
                loadTransactionPort,
                depositStorePort,
                saveSettlementPort,
                new SettlementDepositMatchingPolicy(),
                reader,
                notifications,
                mock(SettlementDepositReconciliationJobStorePort.class),
                new SettlementTransferReconciliationPolicy(),
                Clock.systemUTC(),
                estimateInvalidation
        );
        SettlementBankTransactionFetchService transactionFetchService =
                new SettlementBankTransactionFetchService(
                        bankTransactionPort,
                        bankCatalog,
                        credentialProtection,
                        mock(SettlementBankLookupBudgetPort.class),
                        Clock.systemUTC()
                );
        service = new SettlementDepositService(
                currentActorPort,
                loadSettlementPort,
                loadSnapshotPort,
                connectionStorePort,
                transactionFetchService,
                writer,
                reader
        );
        lenient().when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
    }

    @Test
    @DisplayName("생성자 본인 Share를 제외한 납부가 모두 확인되면 정산을 완료한다")
    void settlementDeposit_allExternalPayersConfirmed_completes() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        Transaction savedTransaction = transaction("6000");
        arrangeFetchedTransaction(savedTransaction);
        when(saveSettlementPort.update(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(result.expectedAmount()).isEqualTo(6_000);
        assertThat(result.confirmedAmount()).isEqualTo(6_000);
        assertThat(result.participantCount()).isOne();
        assertThat(result.completedParticipantCount()).isOne();
        verify(notifications, never()).settlementDepositConfirmed(any(), any(), anyString(), anyLong(), any());
        verify(notifications).settlementCompleted(any(Settlement.class), anyList(), any(LocalDateTime.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementDeposit>> captor = ArgumentCaptor.forClass(List.class);
        verify(depositStorePort).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(deposit -> {
            assertThat(deposit.shareId()).isEqualTo(PAYER_SHARE_ID);
            assertThat(deposit.accountTransactionId()).isEqualTo(savedTransaction.id());
        });
        verify(saveSettlementPort).update(any(Settlement.class));
        InOrder accountUpdateOrder = inOrder(
                bankTransactionPort,
                loadAccountPort,
                loadSettlementPort,
                saveTransactionPort
        );
        // 은행 조회는 스냅샷 생성일부터 현재까지를 월 단위로 나누므로 호출 횟수는 실행 시점에 따라 달라진다.
        // 이 테스트에서는 계좌 갱신보다 먼저 조회하는 순서를 확인한다.
        accountUpdateOrder.verify(bankTransactionPort, atLeastOnce()).fetch(
                eq("CID-1"),
                eq("0020"),
                eq("1002123456789"),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(null)
        );
        accountUpdateOrder.verify(loadAccountPort)
                .findActiveByIdAndUserIdForUpdate(7L, CREATOR_ID);
        accountUpdateOrder.verify(loadSettlementPort).findByIdForUpdate(SETTLEMENT_ID);
        accountUpdateOrder.verify(saveTransactionPort).saveAllIgnoringDuplicates(anyList());
        InOrder duplicateGuardOrder = inOrder(depositStorePort);
        duplicateGuardOrder.verify(depositStorePort)
                .findUnfinishedPayableCandidatesForSettlementByReceivingAccount(
                        SETTLEMENT_ID,
                        CREATOR_ID,
                        7L,
                        "0020",
                        "1002123456789"
                );
        duplicateGuardOrder.verify(depositStorePort)
                .findMatchedExternalTransactionKeysByReceivingAccount(
                        CREATOR_ID,
                        7L,
                        "0020",
                        "1002123456789",
                        Set.of(savedTransaction.externalTransactionKey())
                );
    }

    @Test
    @DisplayName("삭제된 수취 계좌는 잠금 대상 없이 불변 스냅샷으로 입금 대조를 완료한다")
    void settlementDeposit_deletedAccount_reconcilesFromSnapshot() {
        Settlement awaitingSettlement = settlement(SettlementStatus.AWAITING_DEPOSIT);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(awaitingSettlement));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(awaitingSettlement));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(7L, CREATOR_ID))
                .thenReturn(Optional.empty());
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(shares());
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        Transaction savedTransaction = transaction("6000");
        arrangeFetchedTransaction(savedTransaction);
        when(saveSettlementPort.update(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(result.confirmedAmount()).isEqualTo(6_000);
        verify(loadAccountPort).findActiveByIdAndUserIdForUpdate(7L, CREATOR_ID);
        verify(saveTransactionPort).saveAllIgnoringDuplicates(anyList());
        verify(depositStorePort).saveAll(anyList());
    }

    @Test
    @DisplayName("부분 입금은 확인 금액만 누적하고 정산을 입금 대기 상태로 유지한다")
    void settlementDeposit_partialPayment_remainsAwaiting() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        arrangeFetchedTransaction(transaction("3000"));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(result.expectedAmount()).isEqualTo(6_000);
        assertThat(result.confirmedAmount()).isEqualTo(3_000);
        assertThat(result.completedParticipantCount()).isZero();
        verify(saveSettlementPort, never()).update(any(Settlement.class));
        verify(estimateInvalidation).settlementChanged(SETTLEMENT_ID);

    }

    @Test
    @DisplayName("같은 계좌의 다른 정산 Share에도 맞는 거래는 어느 정산에도 자동 연결하지 않는다")
    void settlementDeposit_ambiguousTransaction_notMatched() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        arrangeFetchedTransaction(transaction("6000"));
        when(depositStorePort.findUnfinishedPayableCandidatesForSettlementByReceivingAccount(
                SETTLEMENT_ID,
                CREATOR_ID,
                7L,
                "0020",
                "1002123456789"
        ))
                .thenReturn(List.of(
                        payableCandidate(SETTLEMENT_ID, PAYER_SHARE_ID),
                        payableCandidate(OTHER_SETTLEMENT_ID, OTHER_SHARE_ID)
                ));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(result.confirmedAmount()).isZero();
        verify(depositStorePort).saveAll(List.of());
        verify(saveSettlementPort, never()).update(any(Settlement.class));
        verifyNoInteractions(estimateInvalidation);

    }

    @Test
    @DisplayName("부분 이름만 맞는 거래는 입금을 저장하거나 정산을 완료하지 않는다")
    void settlementDeposit_substringName_notConfirmed() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        arrangeFetchedTransaction(transaction("6000"));
        when(depositStorePort.findUnfinishedPayableCandidatesForSettlementByReceivingAccount(
                SETTLEMENT_ID, CREATOR_ID, 7L, "0020", "1002123456789"
        )).thenReturn(List.of(new SettlementDepositStorePort.PayableDepositCandidate(
                SETTLEMENT_ID, PAYER_SHARE_ID, "선영", 6_000, CREATED_AT
        )));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(result.confirmedAmount()).isZero();
        assertThat(result.completedParticipantCount()).isZero();
        verify(depositStorePort).saveAll(List.of());
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("재연결 전 계좌 생명주기에서 이미 연결한 거래는 다시 연결하지 않는다")
    void settlementDeposit_preReconnectTransaction_notMatchedAgain() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        Transaction savedTransaction = transaction("6000");
        arrangeFetchedTransaction(savedTransaction);
        when(depositStorePort.findMatchedExternalTransactionKeysByReceivingAccount(
                CREATOR_ID,
                7L,
                "0020",
                "1002123456789",
                Set.of(savedTransaction.externalTransactionKey())
        )).thenReturn(Set.of(savedTransaction.externalTransactionKey()));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(result.confirmedAmount()).isZero();
        verify(depositStorePort).saveAll(List.of());
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("완료된 정산의 재대조는 CODEF를 재호출하거나 입금을 중복 저장하지 않는다")
    void settlementDeposit_completedStatus_reconciliationIdempotent() {
        arrangeSettlement(SettlementStatus.COMPLETED);
        Transaction transaction = transaction("6000");
        SettlementDeposit deposit = new SettlementDeposit(
                UUID.randomUUID(),
                SETTLEMENT_ID,
                PAYER_SHARE_ID,
                7L,
                transaction.id(),
                CREATED_AT.plusHours(2)
        );
        when(loadTransactionPort.findIncomeByAccountIdBetween(eq(7L), eq(CREATED_AT), any()))
                .thenReturn(List.of(transaction));
        when(depositStorePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(deposit));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(result.confirmedAmount()).isEqualTo(6_000);
        verify(depositStorePort, never()).saveAll(anyList());
        verifyNoInteractions(connectionStorePort, bankTransactionPort, saveTransactionPort, saveSettlementPort);
    }

    @Test
    @DisplayName("외부 거래 조회 장애는 계좌와 정산 잠금을 획득하기 전에 전파한다")
    void settlementDeposit_externalFailure_beforeDatabaseLocks() {
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.AWAITING_DEPOSIT)));
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(connectionStorePort.findActiveByUserId(CREATOR_ID))
                .thenReturn(Optional.of(new ExternalConnection(3L, CREATOR_ID, "CID-1")));
        when(bankTransactionPort.fetch(
                eq("CID-1"),
                eq("0020"),
                eq("1002123456789"),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(null)
        )).thenThrow(new IllegalStateException("injected external failure"));

        assertThatThrownBy(() -> service.reconcile(SETTLEMENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected external failure");

        verify(loadAccountPort, never())
                .findActiveByIdAndUserIdForUpdate(any(), any());
        verify(loadSettlementPort, never()).findByIdForUpdate(any());
        verifyNoInteractions(saveTransactionPort, depositStorePort, saveSettlementPort);
    }

    @Test
    @DisplayName("계좌 잠금 대기 중 완료된 정산은 잠금 후 상태를 재검증해 저장하지 않는다")
    void settlementDeposit_completedWhileWaitingForLock_notSaved() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.COMPLETED)));
        when(connectionStorePort.findActiveByUserId(CREATOR_ID))
                .thenReturn(Optional.of(new ExternalConnection(3L, CREATOR_ID, "CID-1")));
        when(bankTransactionPort.fetch(
                eq("CID-1"),
                eq("0020"),
                eq("1002123456789"),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(null)
        )).thenReturn(List.of());
        Transaction transaction = transaction("6000");
        when(loadTransactionPort.findIncomeByAccountIdBetween(eq(7L), eq(CREATED_AT), any()))
                .thenReturn(List.of(transaction));
        when(depositStorePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(new SettlementDeposit(
                        UUID.randomUUID(),
                        SETTLEMENT_ID,
                        PAYER_SHARE_ID,
                        7L,
                        transaction.id(),
                        CREATED_AT.plusHours(2)
                )));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.COMPLETED);
        assertThat(result.confirmedAmount()).isEqualTo(6_000);
        verify(saveTransactionPort, never()).saveAllIgnoringDuplicates(anyList());
        verify(depositStorePort, never()).saveAll(anyList());
        verifyNoInteractions(saveSettlementPort);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-04T15:00:00,2026-09-04T15:00:01,2026-09-05,2026-09-05",
            "2026-09-04T15:00:00,2026-09-04T23:59:59,2026-09-05,2026-09-05",
            "2026-09-04T15:00:00,2026-09-05T00:00:00,2026-09-05,2026-09-05",
            "2026-08-31T15:00:00,2026-08-31T16:00:00,2026-09-01,2026-09-01",
            "2026-12-31T15:00:00,2026-12-31T16:00:00,2027-01-01,2027-01-01"
    })
    @DisplayName("KST 00~09시와 월·연 경계에도 UTC 날짜 대신 KST 날짜로 조회한다")
    void settlementDeposit_kstBoundaries_usesKstCalendarDate(String snapshotUtc, String nowUtc, String start, String end) {
        assertQueryPeriods(snapshotUtc, nowUtc, List.of(LocalDate.parse(start)), List.of(LocalDate.parse(end)));
    }

    @Test
    @DisplayName("조회 범위를 KST 달력의 월 경계로 나눈다")
    void settlementDeposit_multipleKstMonths_queriedSeparately() {
        assertQueryPeriods("2026-12-31T14:59:59", "2026-12-31T15:00:01",
                List.of(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)),
                List.of(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)));
    }

    private void assertQueryPeriods(String snapshotUtc, String nowUtc, List<LocalDate> starts, List<LocalDate> ends) {
        var created = LocalDateTime.parse(snapshotUtc);
        var now = LocalDateTime.parse(nowUtc);
        var fixedSnapshot = new SettlementReceivingAccountSnapshot(SETTLEMENT_ID, 7L, "0020",
                "1002123456789", "최수아", created);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.AWAITING_DEPOSIT)));
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(fixedSnapshot));
        when(connectionStorePort.findActiveByUserId(CREATOR_ID))
                .thenReturn(Optional.of(new ExternalConnection(3L, CREATOR_ID, "CID-1")));
        var writer = mock(SettlementDepositWriter.class);
        var queryService = new SettlementDepositService(currentActorPort, loadSettlementPort, loadSnapshotPort,
                connectionStorePort,
                new SettlementBankTransactionFetchService(bankTransactionPort, bankCatalog, credentialProtection,
                        mock(SettlementBankLookupBudgetPort.class),
                        Clock.systemUTC()),
                writer, mock(SettlementDepositReader.class));
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(() -> LocalDateTime.now(ZoneOffset.UTC)).thenReturn(now);
            queryService.reconcile(SETTLEMENT_ID);
        }
        var startCaptor = ArgumentCaptor.forClass(LocalDate.class);
        var endCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(bankTransactionPort, times(starts.size())).fetch(eq("CID-1"), eq("0020"),
                eq("1002123456789"), startCaptor.capture(), endCaptor.capture(), eq(null));
        assertThat(startCaptor.getAllValues()).containsExactlyElementsOf(starts);
        assertThat(endCaptor.getAllValues()).containsExactlyElementsOf(ends);
        verify(writer).reconcile(SETTLEMENT_ID, CREATOR_ID, fixedSnapshot, List.of(), now);
    }

    @Test
    @DisplayName("생성 2시간 이내 입금으로 완료되고 재대조가 중복 입금을 만들지 않는다")
    void settlementDeposit_recentSettlementRetry_noDuplicateDeposit() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        Transaction saved = transaction("6000");
        arrangeFetchedTransaction(saved);
        var now = CREATED_AT.plusHours(2);
        when(saveSettlementPort.update(any(Settlement.class))).thenAnswer(invocation -> invocation.getArgument(0));
        try (var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(() -> LocalDateTime.now(ZoneOffset.UTC)).thenReturn(now);
            assertThat(service.reconcile(SETTLEMENT_ID).status()).isEqualTo(SettlementStatus.COMPLETED);
            when(loadSettlementPort.findById(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(SettlementStatus.COMPLETED)));
            when(loadTransactionPort.findIncomeByAccountIdBetween(7L, CREATED_AT, now)).thenReturn(List.of(saved));
            when(depositStorePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                    new SettlementDeposit(UUID.randomUUID(), SETTLEMENT_ID, PAYER_SHARE_ID, 7L, saved.id(), now)));
            var retried = service.reconcile(SETTLEMENT_ID);
            assertThat(retried.status()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(retried.confirmedAmount()).isEqualTo(6000);
        }
        verify(saveTransactionPort, times(1)).saveAllIgnoringDuplicates(anyList());
        verify(depositStorePort, times(1)).saveAll(anyList());
        verify(saveSettlementPort, times(1)).update(any(Settlement.class));
    }

    @Test
    @DisplayName("REQUESTED 중단 재조회는 현재 정산 ID를 후보 범위로 전달한다")
    void suspensionRefresh_requestedSettlement_scopesPayableCandidatesToTarget() {
        arrangeSettlement(SettlementStatus.REQUESTED);
        when(connectionStorePort.findActiveByUserId(CREATOR_ID))
                .thenReturn(Optional.of(new ExternalConnection(3L, CREATOR_ID, "CID-1")));
        when(bankTransactionPort.fetch(
                eq("CID-1"),
                eq("0020"),
                eq("1002123456789"),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(null)
        )).thenReturn(List.of());

        service.refreshForSuspension(
                SETTLEMENT_ID,
                CREATOR_ID,
                snapshot(),
                SettlementStatus.REQUESTED,
                CREATED_AT.plusHours(2)
        );

        verify(depositStorePort).findUnfinishedPayableCandidatesForSettlementByReceivingAccount(
                SETTLEMENT_ID,
                CREATOR_ID,
                7L,
                "0020",
                "1002123456789"
        );
    }

    private void arrangeSettlement(SettlementStatus status) {
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(status)));
        if (status != SettlementStatus.COMPLETED) {
            when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(status)));
            when(loadAccountPort.findActiveByIdAndUserIdForUpdate(7L, CREATOR_ID))
                    .thenReturn(Optional.of(mock(Account.class)));
        }
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(shares());
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
    }

    private void arrangeFetchedTransaction(Transaction savedTransaction) {
        when(loadTransactionPort.findIncomeByAccountIdBetween(eq(7L), eq(CREATED_AT), any()))
                .thenReturn(List.of(), List.of(savedTransaction));
        when(depositStorePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(depositStorePort.findUnfinishedPayableCandidatesForSettlementByReceivingAccount(
                SETTLEMENT_ID,
                CREATOR_ID,
                7L,
                "0020",
                "1002123456789"
        ))
                .thenReturn(List.of(payableCandidate(SETTLEMENT_ID, PAYER_SHARE_ID)));
        when(depositStorePort.findMatchedExternalTransactionKeysByReceivingAccount(
                CREATOR_ID,
                7L,
                "0020",
                "1002123456789",
                Set.of(savedTransaction.externalTransactionKey())
        )).thenReturn(Set.of());
        when(connectionStorePort.findActiveByUserId(CREATOR_ID))
                .thenReturn(Optional.of(new ExternalConnection(3L, CREATOR_ID, "CID-1")));
        when(bankTransactionPort.fetch(
                eq("CID-1"),
                eq("0020"),
                eq("1002123456789"),
                any(LocalDate.class),
                any(LocalDate.class),
                eq(null)
        )).thenReturn(List.of(new BankTransactionPort.BankTransaction(
                AccountTransactionType.INCOME,
                savedTransaction.amount(),
                savedTransaction.transactedAt(),
                "민선영 입금",
                new BigDecimal("106000")
        )));
    }

    private SettlementDepositStorePort.PayableDepositCandidate payableCandidate(
            UUID settlementId,
            UUID shareId
    ) {
        return new SettlementDepositStorePort.PayableDepositCandidate(
                settlementId,
                shareId,
                "민선영",
                6_000,
                CREATED_AT
        );
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                10_000,
                SettlementAllocationType.DUTCH_PAY,
                7L,
                status,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementReceivingAccountSnapshot snapshot() {
        return new SettlementReceivingAccountSnapshot(
                SETTLEMENT_ID,
                7L,
                "0020",
                "1002123456789",
                "최수아",
                CREATED_AT
        );
    }

    private List<SettlementShare> shares() {
        return List.of(
                new SettlementShare(CREATOR_SHARE_ID, SETTLEMENT_ID, CREATOR_PARTICIPANT_ID, 4_000, CREATED_AT),
                new SettlementShare(PAYER_SHARE_ID, SETTLEMENT_ID, PAYER_PARTICIPANT_ID, 6_000, CREATED_AT)
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                participant(CREATOR_PARTICIPANT_ID, CREATOR_ID, "최수아", 0),
                participant(PAYER_PARTICIPANT_ID, 2L, "민선영", 1)
        );
    }

    private SettlementParticipant participant(
            UUID id,
            Long linkedUserId,
            String displayName,
            int displayOrder
    ) {
        return new SettlementParticipant(
                id,
                SETTLEMENT_ID,
                SettlementParticipantSource.REGISTERED_USER,
                linkedUserId,
                null,
                null,
                displayName,
                displayOrder,
                CREATED_AT,
                CREATED_AT
        );
    }

    private Transaction transaction(String amount) {
        return new Transaction(
                99L,
                7L,
                "a".repeat(64),
                AccountTransactionType.INCOME,
                new BigDecimal(amount),
                CREATED_AT.plusHours(1),
                "민선영 입금",
                new BigDecimal("106000")
        );
    }
}
