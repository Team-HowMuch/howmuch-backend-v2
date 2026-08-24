package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("확정 정산 송금 안내 Application 흐름")
class SettlementTransferInstructionServiceTest {
    private static final String RAW_TOKEN = "x".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final UUID CONTEXT_ID = UUID.fromString("074dc83c-f0aa-4bf6-8f6b-d17a415620c7");
    private static final UUID SHARE_ID = UUID.fromString("0d90a9cd-c9d6-49d0-9217-4c105b9fb3da");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Mock private SettlementLinkTokenPort linkTokenPort;
    @Mock private LoadSettlementLinkPort loadSettlementLinkPort;
    @Mock private LoadSettlementParticipantContextPort loadParticipantContextPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;

    private SettlementTransferInstructionService service;

    @BeforeEach
    void setUp() {
        service = new SettlementTransferInstructionService(
                linkTokenPort,
                loadSettlementLinkPort,
                loadParticipantContextPort,
                loadSettlementPort,
                loadSettlementSharePort,
                loadSnapshotPort
        );
    }

    @Test
    @DisplayName("참여 문맥이 가리키는 참여자의 확정 금액과 수금 계좌만 반환한다")
    void getsTransferInstructionForSelectedParticipant() {
        arrangeLinkAndContext();
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.AWAITING_DEPOSIT)));
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID,
                PARTICIPANT_ID
        )).thenReturn(Optional.of(new SettlementShare(
                SHARE_ID,
                SETTLEMENT_ID,
                PARTICIPANT_ID,
                6_000,
                CREATED_AT
        )));
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));

        var result = service.get(RAW_TOKEN, CONTEXT_ID);

        assertThat(result.participantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(result.shareId()).isEqualTo(SHARE_ID);
        assertThat(result.amount()).isEqualTo(6_000);
        assertThat(result.recipientBankCode()).isEqualTo("0020");
        assertThat(result.recipientAccountNumber()).isEqualTo("1002123456789");
    }

    @Test
    @DisplayName("다른 링크에서 발급된 참여 문맥으로는 송금 정보를 조회할 수 없다")
    void rejectsContextIssuedForAnotherLink() {
        arrangeLink();
        when(loadParticipantContextPort.findByToken(CONTEXT_ID)).thenReturn(Optional.of(
                new SettlementParticipantContext(
                        CONTEXT_ID,
                        "b".repeat(64),
                        SETTLEMENT_ID,
                        PARTICIPANT_ID,
                        CREATED_AT,
                        LocalDateTime.of(2099, 1, 1, 0, 0)
                )
        ));

        assertThatThrownBy(() -> service.get(RAW_TOKEN, CONTEXT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND
                        ));
        verify(loadSettlementPort, never()).findById(SETTLEMENT_ID);
    }

    @ParameterizedTest(name = "{0} 상태")
    @EnumSource(
            value = SettlementStatus.class,
            names = {"DRAFT", "REQUESTED", "SUSPENDED", "COMPLETED"}
    )
    @DisplayName("입금 대기 상태가 아니면 확정 송금 정보를 노출하지 않는다")
    void rejectsSettlementBeforeTransferReady(SettlementStatus status) {
        arrangeLinkAndContext();
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(status)));

        assertThatThrownBy(() -> service.get(RAW_TOKEN, CONTEXT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                        ));
        verify(loadSettlementSharePort, never())
                .findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, PARTICIPANT_ID);
    }

    @Test
    @DisplayName("확정 Share 또는 수금 계좌가 누락되면 불완전한 송금 정보를 반환하지 않는다")
    void rejectsIncompleteTransferInstruction() {
        arrangeLinkAndContext();
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.AWAITING_DEPOSIT)));
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID,
                PARTICIPANT_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(RAW_TOKEN, CONTEXT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                        ));
        verify(loadSnapshotPort, never()).findBySettlementId(SETTLEMENT_ID);
    }

    private void arrangeLinkAndContext() {
        arrangeLink();
        when(loadParticipantContextPort.findByToken(CONTEXT_ID)).thenReturn(Optional.of(
                new SettlementParticipantContext(
                        CONTEXT_ID,
                        TOKEN_HASH,
                        SETTLEMENT_ID,
                        PARTICIPANT_ID,
                        CREATED_AT,
                        LocalDateTime.of(2099, 1, 1, 0, 0)
                )
        ));
    }

    private void arrangeLink() {
        when(linkTokenPort.hash(RAW_TOKEN)).thenReturn(TOKEN_HASH);
        when(loadSettlementLinkPort.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(
                new SettlementLink(
                        UUID.randomUUID(),
                        SETTLEMENT_ID,
                        TOKEN_HASH,
                        SettlementLinkStatus.ACTIVE,
                        LocalDateTime.of(2099, 1, 1, 0, 0),
                        CREATED_AT,
                        CREATED_AT
                )
        ));
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                1L,
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
}
