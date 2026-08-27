package com.amateur.howmuchbackend.application.settlement.service;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 입금 대조 Application 흐름")
class SettlementDepositServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID OTHER_SETTLEMENT_ID = UUID.fromString("56d2237c-714e-46b0-a036-e5f44f8f509a");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("bb97ee76-3a82-4ccc-b324-4a9974c8e1af");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final UUID CREATOR_SHARE_ID = UUID.fromString("9de946eb-bf2e-4fbb-b5e2-61d76098669c");
    private static final UUID PAYER_SHARE_ID = UUID.fromString("0d90a9cd-c9d6-49d0-9217-4c105b9fb3da");
    private static final UUID OTHER_SHARE_ID = UUID.fromString("14bdff25-6157-4fac-bbf5-cb4605704aae");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 20, 0, 0);

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

    private SettlementDepositService service;

    @BeforeEach
    void setUp() {
        service = new SettlementDepositService(
                currentActorPort,
                loadSettlementPort,
                saveSettlementPort,
                loadSettlementSharePort,
                loadSettlementParticipantPort,
                loadSnapshotPort,
                depositStorePort,
                loadAccountPort,
                loadTransactionPort,
                saveTransactionPort,
                connectionStorePort,
                bankTransactionPort,
                new SettlementDepositMatchingPolicy()
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
    }

    @Test
    @DisplayName("생성자 본인 Share를 제외한 납부가 모두 확인되면 정산을 완료한다")
    void completesWhenEveryExternalPayerIsConfirmed() {
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementDeposit>> captor = ArgumentCaptor.forClass(List.class);
        verify(depositStorePort).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(deposit -> {
            assertThat(deposit.shareId()).isEqualTo(PAYER_SHARE_ID);
            assertThat(deposit.accountTransactionId()).isEqualTo(savedTransaction.id());
        });
        verify(saveSettlementPort).update(any(Settlement.class));
        InOrder accountUpdateOrder = inOrder(
                loadAccountPort,
                loadSettlementPort,
                saveTransactionPort
        );
        accountUpdateOrder.verify(loadAccountPort)
                .findActiveByIdAndUserIdForUpdate(7L, CREATOR_ID);
        accountUpdateOrder.verify(loadSettlementPort).findByIdForUpdate(SETTLEMENT_ID);
        accountUpdateOrder.verify(saveTransactionPort).saveAllIgnoringDuplicates(anyList());
        InOrder duplicateGuardOrder = inOrder(depositStorePort);
        duplicateGuardOrder.verify(depositStorePort)
                .findUnfinishedPayableCandidatesByReceivingAccount(
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
    @DisplayName("부분 입금은 확인 금액만 누적하고 정산을 입금 대기 상태로 유지한다")
    void keepsAwaitingDepositAfterPartialPayment() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        arrangeFetchedTransaction(transaction("3000"));

        var result = service.reconcile(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(result.expectedAmount()).isEqualTo(6_000);
        assertThat(result.confirmedAmount()).isEqualTo(3_000);
        assertThat(result.completedParticipantCount()).isZero();
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("같은 계좌의 다른 정산 Share에도 맞는 거래는 어느 정산에도 자동 연결하지 않는다")
    void rejectsTransactionAmbiguousAcrossSettlements() {
        arrangeSettlement(SettlementStatus.AWAITING_DEPOSIT);
        arrangeFetchedTransaction(transaction("6000"));
        when(depositStorePort.findUnfinishedPayableCandidatesByReceivingAccount(
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
    }

    @Test
    @DisplayName("재연결 전 계좌 생명주기에서 이미 연결한 거래는 다시 연결하지 않는다")
    void rejectsTransactionMatchedBeforeAccountReconnection() {
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
    void returnsCompletedSummaryIdempotently() {
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
    @DisplayName("계좌 잠금 대기 중 완료된 정산은 잠금 후 상태를 재검증해 저장하지 않는다")
    void rechecksCompletedStatusAfterAccountLock() {
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
                any(LocalDate.class)
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

    private void arrangeSettlement(SettlementStatus status) {
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(status)));
        if (status != SettlementStatus.COMPLETED) {
            when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(status)));
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
        when(depositStorePort.findUnfinishedPayableCandidatesByReceivingAccount(
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
                any(LocalDate.class)
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
