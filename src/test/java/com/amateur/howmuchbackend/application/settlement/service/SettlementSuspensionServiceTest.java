package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementSuspensionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 중단 coordinator")
class SettlementSuspensionServiceTest {
    private static final Long CREATOR_ID = 11L;
    private static final Long PAYER_ID = 22L;
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 1, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private SettlementSuspensionReader reader;
    @Mock private SettlementSuspensionWriter writer;
    @Mock private SettlementSuspensionPolicy policy;
    @Mock private SettlementDepositService depositService;

    private SettlementSuspensionService service;
    private SettlementSuspensionContext context;
    private Settlement settlement;
    private SettlementReceivingAccountSnapshot snapshot;
    private SettlementParticipant creator;
    private SettlementParticipant payer;

    @BeforeEach
    void setUp() {
        service = new SettlementSuspensionService(
                currentActorPort, reader, writer, policy, depositService
        );
        settlement = new Settlement(
                SETTLEMENT_ID, CREATOR_ID, "공동 정산", 1000,
                SettlementAllocationType.ITEM_ALLOCATION, 7L,
                SettlementStatus.REQUESTED, NOW, NOW
        );
        snapshot = new SettlementReceivingAccountSnapshot(
                SETTLEMENT_ID, 7L, "0020", "1002123456789", "최수아", NOW
        );
        creator = new SettlementParticipant(
                UUID.randomUUID(), SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                CREATOR_ID, null, null, "최수아", 0, NOW, NOW
        );
        payer = new SettlementParticipant(
                UUID.randomUUID(), SETTLEMENT_ID, SettlementParticipantSource.REGISTERED_USER,
                PAYER_ID, null, null, "민선영", 1, NOW, NOW
        );
        context = new SettlementSuspensionContext(
                settlement, snapshot, List.of(creator, payer), List.of(), List.of()
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(reader.prepare(SETTLEMENT_ID, CREATOR_ID)).thenReturn(context);
        lenient().when(writer.suspend(any(), eq(CREATOR_ID), any()))
                .thenAnswer(invocation -> {
                    SettlementSuspensionContext expected = invocation.getArgument(0);
                    return SettlementResult.from(expected.settlement()
                            .suspendManually(NOW.plusMinutes(1)));
                });
    }

    @Test
    @DisplayName("송금 가능 상태가 없으면 외부 조회 없이 기존 중단 writer를 호출한다")
    void suspendWithoutPossibleTransfer_skipsRefresh() {
        when(policy.requiresDepositRefresh(
                settlement, context.shares(), context.participants(), context.submissions()
        )).thenReturn(false);

        SettlementResult result = service.suspend(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.SUSPENDED);
        verify(depositService, never()).refreshForSuspension(
                any(), any(), any(), any(), any()
        );
        verify(writer).suspend(context, CREATOR_ID, null);
    }

    @Test
    @DisplayName("다른 참여자의 제출 기록이 있으면 외부 재조회 후 final writer를 호출한다")
    void suspendWithOtherParticipantSubmission_refreshesBeforeFinalWriter() {
        SettlementItemSubmission submission = SettlementItemSubmission.submit(
                SETTLEMENT_ID, payer.id(), NOW.plusMinutes(1)
        );
        context = new SettlementSuspensionContext(
                settlement, snapshot, List.of(creator, payer), List.of(), List.of(submission)
        );
        when(reader.prepare(SETTLEMENT_ID, CREATOR_ID)).thenReturn(context);
        when(policy.requiresDepositRefresh(
                settlement, context.shares(), context.participants(), context.submissions()
        )).thenReturn(true);

        SettlementResult result = service.suspend(SETTLEMENT_ID);

        assertThat(result.status()).isEqualTo(SettlementStatus.SUSPENDED);
        verify(depositService).refreshForSuspension(
                eq(SETTLEMENT_ID), eq(CREATOR_ID), eq(snapshot), eq(SettlementStatus.REQUESTED), any()
        );
        verify(writer).suspend(eq(context), eq(CREATOR_ID), any());
    }

    @Test
    @DisplayName("입금 재조회가 실패하면 중단하지 않는다")
    void suspendWhenRefreshFails_doesNotCallFinalWriter() {
        when(policy.requiresDepositRefresh(
                settlement, context.shares(), context.participants(), context.submissions()
        )).thenReturn(true);
        doThrow(ApplicationException.of(SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND))
                .when(depositService).refreshForSuspension(
                eq(SETTLEMENT_ID), eq(CREATOR_ID), eq(snapshot), eq(SettlementStatus.REQUESTED), any()
        );

        assertThatThrownBy(() -> service.suspend(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND));
        verify(writer, never()).suspend(any(), any(), any());
    }

    @Test
    @DisplayName("재조회가 필요한데 수금 계좌 snapshot이 없으면 fail closed 한다")
    void suspendWithoutSnapshot_whenRefreshNeeded_rejected() {
        context = new SettlementSuspensionContext(
                settlement, null, context.participants(), context.shares(), context.submissions()
        );
        when(reader.prepare(SETTLEMENT_ID, CREATOR_ID)).thenReturn(context);
        when(policy.requiresDepositRefresh(
                settlement, context.shares(), context.participants(), context.submissions()
        )).thenReturn(true);

        assertThatThrownBy(() -> service.suspend(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND));
        verify(depositService, never()).refreshForSuspension(
                any(), any(), any(), any(), any()
        );
        verify(writer, never()).suspend(any(), any(), any());
    }
}
