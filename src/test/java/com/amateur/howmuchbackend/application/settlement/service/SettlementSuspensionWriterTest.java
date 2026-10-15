package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDepositStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementSuspensionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 중단 final writer")
class SettlementSuspensionWriterTest {
    private static final Long CREATOR_ID = 11L;
    private static final Long PAYER_ID = 22L;
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 1, 0);

    @Mock private LoadAccountPort loadAccountPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementReceivingAccountSnapshotPort snapshotPort;
    @Mock private LoadSettlementParticipantPort participantPort;
    @Mock private LoadSettlementSharePort sharePort;
    @Mock private SettlementItemAllocationStorePort allocationStorePort;
    @Mock private SettlementDepositReader depositReader;
    @Mock private SettlementDepositStorePort depositStorePort;
    @Mock private SaveSettlementPort saveSettlementPort;

    private SettlementSuspensionPolicy policy;
    private SettlementSuspensionWriter writer;
    private Settlement settlement;
    private SettlementParticipant creator;
    private SettlementParticipant payer;
    private SettlementReceivingAccountSnapshot snapshot;

    @BeforeEach
    void setUp() {
        policy = new SettlementSuspensionPolicy();
        writer = new SettlementSuspensionWriter(
                loadAccountPort,
                loadSettlementPort,
                snapshotPort,
                participantPort,
                sharePort,
                allocationStorePort,
                depositReader,
                depositStorePort,
                policy,
                saveSettlementPort
        );
        settlement = new Settlement(
                SETTLEMENT_ID, CREATOR_ID, "공동 정산", 1000,
                SettlementAllocationType.ITEM_ALLOCATION, 7L,
                SettlementStatus.REQUESTED, NOW, NOW
        );
        creator = new SettlementParticipant(
                UUID.randomUUID(), SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                CREATOR_ID, null, null, "최수아", 0, NOW, NOW
        );
        payer = new SettlementParticipant(
                UUID.randomUUID(), SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                PAYER_ID, null, null, "민선영", 1, NOW, NOW
        );
        snapshot = new SettlementReceivingAccountSnapshot(
                SETTLEMENT_ID, 7L, "0020", "1002123456789", "최수아", NOW
        );
        lenient().when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement));
        lenient().when(snapshotPort.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.empty());
        lenient().when(participantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(creator, payer));
        lenient().when(sharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        lenient().when(allocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of());
        lenient().when(saveSettlementPort.update(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("조회 이후 Share가 추가되면 최신 상태 기준으로 중단을 거절한다")
    void suspendWhenShareChangesAfterPreflight_rejectedAsConcurrentUpdate() {
        SettlementSuspensionContext context = context(List.of(), List.of());
        when(sharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementShare.create(SETTLEMENT_ID, payer.id(), 1000, NOW)
        ));

        assertThatThrownBy(() -> writer.suspend(context, CREATOR_ID, null))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(CommonStatusCode.CONCURRENT_UPDATE));
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("계좌가 조회 중 비활성화되면 최종 중단을 거절한다")
    void suspendWhenReceivingAccountInactive_rejected() {
        Settlement awaiting = settlement.awaitDeposit(NOW.plusMinutes(1));
        SettlementReceivingAccountSnapshot awaitingSnapshot = new SettlementReceivingAccountSnapshot(
                SETTLEMENT_ID, 7L, "0020", "1002123456789", "최수아", NOW
        );
        SettlementSuspensionContext context = new SettlementSuspensionContext(
                awaiting, awaitingSnapshot, List.of(creator, payer), List.of(), List.of()
        );
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(7L, CREATOR_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> writer.suspend(context, CREATOR_ID, NOW.plusMinutes(2)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(CommonStatusCode.CONCURRENT_UPDATE));
        verify(loadSettlementPort, never()).findByIdForUpdate(SETTLEMENT_ID);
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("재조회 결과의 미귀속 양수 INCOME은 모호한 입금으로 보고 거절한다")
    void suspendWhenUnmatchedPositiveIncomeExists_rejected() {
        Settlement awaiting = settlement.awaitDeposit(NOW.plusMinutes(1));
        SettlementShare share = SettlementShare.create(SETTLEMENT_ID, payer.id(), 1000, NOW);
        SettlementSuspensionContext context = new SettlementSuspensionContext(
                awaiting, snapshot, List.of(creator, payer), List.of(share), List.of()
        );
        SettlementDepositReader.DepositData data = new SettlementDepositReader.DepositData(
                snapshot,
                List.of(new SettlementDepositReader.PayableShare(share, "민선영", PAYER_ID)),
                java.util.Map.of(),
                List.of(new Transaction(
                        10L,
                        7L,
                        "a".repeat(64),
                        AccountTransactionType.INCOME,
                        BigDecimal.valueOf(250),
                        NOW.plusMinutes(1),
                        "모르는 입금",
                        BigDecimal.valueOf(10_250),
                        null,
                        null,
                        null,
                        null
                ))
        );
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(7L, CREATOR_ID))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(com.amateur.howmuchbackend.domain.account.Account.class)));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(awaiting));
        when(snapshotPort.findBySettlementId(SETTLEMENT_ID)).thenReturn(Optional.of(snapshot));
        when(sharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(share));
        when(depositReader.load(eq(awaiting), any(LocalDateTime.class))).thenReturn(data);
        when(depositStorePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(depositStorePort.findMatchedExternalTransactionKeysByReceivingAccount(
                eq(CREATOR_ID), eq(7L), eq("0020"), eq("1002123456789"), eq(Set.of("a".repeat(64)))
        )).thenReturn(Set.of());

        assertThatThrownBy(() -> writer.suspend(context, CREATOR_ID, NOW.plusMinutes(2)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE));
        verify(saveSettlementPort, never()).update(any(Settlement.class));
    }

    @Test
    @DisplayName("입금 이력이 없으면 생성자 중단을 저장한다")
    void suspendWithoutPayment_savesSuspendedState() {
        SettlementResult result = writer.suspend(context(List.of(), List.of()), CREATOR_ID, null);

        assertThat(result.status()).isEqualTo(SettlementStatus.SUSPENDED);
        verify(saveSettlementPort).update(any(Settlement.class));
    }

    private SettlementSuspensionContext context(
            List<SettlementShare> shares,
            List<SettlementItemSubmission> submissions
    ) {
        return new SettlementSuspensionContext(
                settlement,
                null,
                List.of(creator, payer),
                shares,
                submissions
        );
    }
}
