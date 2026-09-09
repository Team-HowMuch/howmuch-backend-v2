package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원 참여자 송금 안내 Application 흐름")
class SettlementMemberTransferInstructionServiceTest {
    private static final long MY_USER_ID = 11L;
    private static final long OTHER_USER_ID = 22L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID MY_PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final UUID OTHER_PARTICIPANT_ID = UUID.fromString("2b1c9f30-9c1c-4c7f-9a3f-1d6a1f0a77c1");
    private static final UUID SHARE_ID = UUID.fromString("0d90a9cd-c9d6-49d0-9217-4c105b9fb3da");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private LoadSettlementDepositSummaryPort loadDepositSummaryPort;
    @Mock private LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    @Mock private SettlementTransferLinkPort transferLinkPort;
    @Mock private BankCatalog bankCatalog;

    private SettlementMemberTransferInstructionService service;

    @BeforeEach
    void setUp() {
        service = new SettlementMemberTransferInstructionService(
                currentActorPort,
                loadSettlementPort,
                loadSettlementParticipantPort,
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
    @DisplayName("링크 없이도 로그인한 참여자의 확정 금액과 수금 계좌를 반환한다")
    void getsMyTransferInstructionWithoutLink() {
        arrangeActor(MY_USER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.AWAITING_DEPOSIT)));
        arrangeParticipants();
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID,
                MY_PARTICIPANT_ID
        )).thenReturn(Optional.of(new SettlementShare(
                SHARE_ID,
                SETTLEMENT_ID,
                MY_PARTICIPANT_ID,
                6_000,
                CREATED_AT
        )));
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(transferLinkPort.create("0020", "1002123456789", 6_000, "최수아"))
                .thenReturn(Optional.of(new SettlementTransferLinkPort.SettlementTransferLink(
                        "TOSS",
                        "supertoss://send?accountNo=1002123456789&amount=6000"
                )));

        var result = service.getMyTransferInstruction(SETTLEMENT_ID);

        assertThat(result.participantId()).isEqualTo(MY_PARTICIPANT_ID);
        assertThat(result.shareId()).isEqualTo(SHARE_ID);
        assertThat(result.amount()).isEqualTo(6_000);
        assertThat(result.recipientAccountNumber()).isEqualTo("1002123456789");
        assertThat(result.transferLinks()).hasSize(1);
    }

    @Test
    @DisplayName("이 정산의 참여자가 아니면 남의 송금 정보를 볼 수 없다")
    void rejectsNonParticipant() {
        arrangeActor(OTHER_USER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.AWAITING_DEPOSIT)));
        arrangeParticipants();

        assertThatThrownBy(() -> service.getMyTransferInstruction(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_ACCESS_DENIED
                        ));
        verify(loadSettlementSharePort, never())
                .findBySettlementIdAndPayerParticipantId(SETTLEMENT_ID, MY_PARTICIPANT_ID);
    }

    @Test
    @DisplayName("정산이 없으면 참여자를 찾기 전에 거부한다")
    void rejectsMissingSettlement() {
        arrangeActor(MY_USER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyTransferInstruction(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_NOT_FOUND
                        ));
    }

    @Test
    @DisplayName("전원이 끝나지 않았어도 내 Share가 있으면 보낼 수 있다")
    void allowsTransferBeforeEveryoneSubmits() {
        arrangeActor(MY_USER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.REQUESTED)));
        arrangeParticipants();
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID,
                MY_PARTICIPANT_ID
        )).thenReturn(Optional.of(new SettlementShare(
                SHARE_ID,
                SETTLEMENT_ID,
                MY_PARTICIPANT_ID,
                6_000,
                CREATED_AT
        )));
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(transferLinkPort.create("0020", "1002123456789", 6_000, "최수아"))
                .thenReturn(Optional.of(new SettlementTransferLinkPort.SettlementTransferLink(
                        "TOSS",
                        "supertoss://send?accountNo=1002123456789&amount=6000"
                )));

        var result = service.getMyTransferInstruction(SETTLEMENT_ID);

        assertThat(result.amount()).isEqualTo(6_000);
    }

    @Test
    @DisplayName("아무도 안 고른 품목이 남아 멈춰도 내 몫은 보낼 수 있다")
    void allowsTransferWhileSuspended() {
        arrangeActor(MY_USER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.SUSPENDED)));
        arrangeParticipants();
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID,
                MY_PARTICIPANT_ID
        )).thenReturn(Optional.of(new SettlementShare(
                SHARE_ID,
                SETTLEMENT_ID,
                MY_PARTICIPANT_ID,
                6_000,
                CREATED_AT
        )));
        when(loadSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(snapshot()));
        when(transferLinkPort.create("0020", "1002123456789", 6_000, "최수아"))
                .thenReturn(Optional.of(new SettlementTransferLinkPort.SettlementTransferLink(
                        "TOSS",
                        "supertoss://send?accountNo=1002123456789&amount=6000"
                )));

        assertThat(service.getMyTransferInstruction(SETTLEMENT_ID).amount())
                .isEqualTo(6_000);
    }

    @Test
    @DisplayName("내 Share가 아직 없으면 보낼 금액이 없다")
    void rejectsWhenMyShareIsMissing() {
        arrangeActor(MY_USER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.REQUESTED)));
        arrangeParticipants();
        when(loadSettlementSharePort.findBySettlementIdAndPayerParticipantId(
                SETTLEMENT_ID,
                MY_PARTICIPANT_ID
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyTransferInstruction(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND
                        ));
    }

    private void arrangeActor(long userId) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
    }

    private void arrangeParticipants() {
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(
                        participant(OTHER_PARTICIPANT_ID, 99L),
                        participant(MY_PARTICIPANT_ID, MY_USER_ID)
                ));
    }

    private SettlementParticipant participant(UUID participantId, Long userId) {
        return new SettlementParticipant(
                participantId,
                SETTLEMENT_ID,
                SettlementParticipantSource.REGISTERED_USER,
                userId,
                null,
                null,
                "참여자",
                0,
                CREATED_AT,
                CREATED_AT
        );
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
