package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private LoadSettlementDepositSummaryPort loadDepositSummaryPort;
    @Mock private LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    @Mock private SettlementTransferLinkPort transferLinkPort;
    @Mock private BankCatalog bankCatalog;

    private SettlementTransferInstructionService service;

    @BeforeEach
    void setUp() {
        service = new SettlementTransferInstructionService(
                linkTokenPort,
                loadSettlementLinkPort,
                loadParticipantContextPort,
                new SettlementTransferInstructionReader(
                        loadSettlementPort,
                        loadSettlementSharePort,
                        loadDepositSummaryPort,
                        loadSnapshotPort,
                        List.of(transferLinkPort),
                        bankCatalog
                )
        );
    }

    @Test
    @DisplayName("참여 문맥이 가리키는 참여자의 확정 금액과 수금 계좌만 반환한다")
    void transferInstruction_selected_returnsAmountAndAccount() {
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
        when(transferLinkPort.create("0020", "1002123456789", 6_000, "최수아"))
                .thenReturn(Optional.of(new SettlementTransferLinkPort.SettlementTransferLink(
                        "TOSS",
                        "supertoss://send?bank=%EC%9A%B0%EB%A6%AC%EC%9D%80%ED%96%89"
                                + "&accountNo=1002123456789&amount=6000"
                )));
        when(bankCatalog.findBankName("0020")).thenReturn(Optional.of("우리은행"));

        var result = service.get(RAW_TOKEN, CONTEXT_ID);

        assertThat(result.participantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(result.shareId()).isEqualTo(SHARE_ID);
        assertThat(result.amount()).isEqualTo(6_000);
        // 송금 화면에 수취인 이름과 은행 이름을 표시하도록 결과에 함께 담는다.
        assertThat(result.recipientName()).isEqualTo("최수아");
        assertThat(result.recipientBankCode()).isEqualTo("0020");
        assertThat(result.recipientBankName()).isEqualTo("우리은행");
        assertThat(result.recipientAccountNumber()).isEqualTo("1002123456789");
        assertThat(result.transferLinks()).containsExactly(
                new SettlementTransferInstructionResult.TransferLink(
                        "TOSS",
                        "supertoss://send?bank=%EC%9A%B0%EB%A6%AC%EC%9D%80%ED%96%89"
                                + "&accountNo=1002123456789&amount=6000"
                )
        );
    }

    @Test
    @DisplayName("은행 이름을 찾지 못하면 코드를 그대로 돌려준다")
    void transferInstruction_unknownBankName_returnsBankCode() {
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
        when(transferLinkPort.create("0020", "1002123456789", 6_000, "최수아"))
                .thenReturn(Optional.of(new SettlementTransferLinkPort.SettlementTransferLink(
                        "TOSS",
                        "supertoss://send?bank=x&accountNo=1002123456789&amount=6000"
                )));
        when(bankCatalog.findBankName("0020")).thenReturn(Optional.empty());

        var result = service.get(RAW_TOKEN, CONTEXT_ID);

        assertThat(result.recipientBankName()).isEqualTo("0020");
    }

    @Test
    @DisplayName("지원하는 송금 앱 링크가 없으면 원계좌번호만 반환하지 않는다")
    void transferInstruction_noSupportedAppLink_rejected() {
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
        when(transferLinkPort.create("0020", "1002123456789", 6_000, "최수아"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(RAW_TOKEN, CONTEXT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED
                        ));
    }

    @Test
    @DisplayName("다른 링크에서 발급된 참여 문맥으로는 송금 정보를 조회할 수 없다")
    void transferInstruction_contextFromAnotherLink_rejected() {
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
    @EnumSource(value = SettlementStatus.class, names = {"DRAFT", "COMPLETED"})
    @DisplayName("보낼 수 없는 상태면 확정 송금 정보를 노출하지 않는다")
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

    @ParameterizedTest(name = "{0}: 납부 {1}원 → 잔액 {2}원")
    @CsvSource({
            "AWAITING_DEPOSIT, 0, 10000",
            "AWAITING_DEPOSIT, 4000, 6000",
            "AWAITING_DEPOSIT, 10000, 0",
            "AWAITING_DEPOSIT, 12000, 0",
            "AWAITING_DEPOSIT, 9223372036854775807, 0",
            "REQUESTED, 4000, 6000",
            "SUSPENDED, 4000, 6000"
    })
    @DisplayName("본인 Share의 확인된 입금만 차감하고 완납이면 링크를 생성하지 않는다")
    void reflectsRemainingAmount(SettlementStatus status, long confirmedAmount, long expectedAmount) {
        arrangeLinkAndContext();
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(status)));
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID, PARTICIPANT_ID
        )).thenReturn(Optional.of(new SettlementShare(
                SHARE_ID, SETTLEMENT_ID, PARTICIPANT_ID, 10_000, CREATED_AT
        )));
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));
        var otherShare = new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(UUID.randomUUID(), 9_000);
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(confirmedAmount == 0 ? List.of(otherShare) : List.of(
                        otherShare,
                        new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(SHARE_ID, confirmedAmount)
                ));
        String url = "supertoss://send?accountNo=1002123456789&amount=" + expectedAmount;
        if (expectedAmount > 0) {
            when(transferLinkPort.create("0020", "1002123456789", expectedAmount, "최수아"))
                    .thenReturn(Optional.of(new SettlementTransferLinkPort.SettlementTransferLink("TOSS", url)));
        }

        var result = service.get(RAW_TOKEN, CONTEXT_ID);

        assertThat(result.amount()).isEqualTo(expectedAmount);
        assertThat(result.shareId()).isEqualTo(SHARE_ID);
        assertThat(result.participantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(result.recipientAccountNumber()).isEqualTo("1002123456789");
        if (expectedAmount == 0) {
            assertThat(result.transferLinks()).isEmpty();
            verifyNoInteractions(transferLinkPort);
        } else {
            assertThat(result.transferLinks()).singleElement()
                    .satisfies(link -> assertThat(link.url()).isEqualTo(url));
            verify(transferLinkPort).create("0020", "1002123456789", expectedAmount, "최수아");
        }
        verify(loadDepositSummaryPort).findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID);
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
