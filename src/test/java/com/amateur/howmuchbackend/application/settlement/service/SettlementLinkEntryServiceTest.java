package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryMode;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.IssueSettlementParticipantContextPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementLinkPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementLink;
import com.amateur.howmuchbackend.domain.settlement.SettlementLinkStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantContext;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("공개 정산 링크 진입 Application 흐름")
class SettlementLinkEntryServiceTest {
    private static final String RAW_TOKEN = "x".repeat(43);
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("bb97ee76-3a82-4ccc-b324-4a9974c8e1af");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final UUID CONTEXT_ID = UUID.fromString("074dc83c-f0aa-4bf6-8f6b-d17a415620c7");
    private static final LocalDateTime SETTLEMENT_CREATED_AT = LocalDateTime.of(2026, 8, 24, 9, 0);
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 24, 10, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 8, 24, 19, 30);

    @Mock private SettlementLinkTokenPort linkTokenPort;
    @Mock private LoadSettlementLinkPort loadSettlementLinkPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private IssueSettlementParticipantContextPort issueParticipantContextPort;

    private SettlementLinkEntryService service;

    @BeforeEach
    void setUp() {
        service = new SettlementLinkEntryService(
                linkTokenPort,
                loadSettlementLinkPort,
                loadSettlementPort,
                loadSettlementParticipantPort,
                loadSettlementSharePort,
                currentActorPort,
                loadSettlementExpensePort,
                issueParticipantContextPort
        );
    }

    @Test
    @DisplayName("비로그인 사용자는 표시 순서의 참여자 목록에서 본인을 선택한다")
    void settlementLink_browserUser_selectsParticipantByOrder() {
        arrangeDutchPayAccess();
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.empty());

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.entryMode()).isEqualTo(SettlementLinkEntryMode.BROWSER_SELECTION);
        assertThat(result.allocationPhase()).isEqualTo(SettlementRequestPhase.TRANSFER_READY);
        assertThat(result.requestedAt()).isEqualTo(CREATED_AT);
        assertThat(result.totalAmount()).isEqualTo(10_000);
        assertThat(result.participantCount()).isEqualTo(2);
        assertThat(result.participantCandidates())
                .extracting(candidate -> candidate.displayName())
                .containsExactly("최수아", "민선영");
        assertThat(result.participantCandidates())
                .extracting(candidate -> candidate.isRequester())
                .containsExactly(true, false);
        assertThat(result.participantCandidates())
                .extracting(candidate -> candidate.amount())
                .containsExactly(4_000L, 6_000L);
        assertThat(result.selectedParticipant()).isNull();
        assertThat(result.participantContext()).isNull();
        verify(issueParticipantContextPort, never()).issue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("APP_DIRECT도 요청자 포함 전체 인원과 미확정 금액 null을 반환한다")
    void settlementLink_appDirect_returnsParticipantsAndAmounts() {
        arrangeDutchPayAccess();
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(shares().getFirst()));
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.of(new CurrentActor(2L)));
        arrangeContext(PAYER_PARTICIPANT_ID);

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.entryMode()).isEqualTo(SettlementLinkEntryMode.APP_DIRECT);
        assertThat(result.requestedAt()).isEqualTo(CREATED_AT);
        assertThat(result.totalAmount()).isEqualTo(10_000);
        assertThat(result.participantCount()).isEqualTo(2);
        assertThat(result.selectedParticipant().participantId()).isEqualTo(PAYER_PARTICIPANT_ID);
        assertThat(result.selectedParticipant().isRequester()).isFalse();
        assertThat(result.selectedParticipant().amount()).isNull();
        assertThat(result.participantCandidates()).isEmpty();
        assertThat(result.participantContext()).isEqualTo(CONTEXT_ID.toString());
    }

    @Test
    @DisplayName("로그인했어도 연결된 참여자가 없으면 신뢰 기반 이름 선택으로 전환한다")
    void fallsBackToBrowserSelectionWhenUserIsNotLinked() {
        arrangeItemAllocationAccess();
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.of(new CurrentActor(99L)));

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.entryMode()).isEqualTo(SettlementLinkEntryMode.BROWSER_SELECTION);
        assertThat(result.participantCandidates()).hasSize(2);
        assertThat(result.participantCandidates())
                .extracting(candidate -> candidate.amount())
                .containsOnlyNulls();
        verify(loadSettlementSharePort, never()).findAllBySettlementId(any());
        verify(issueParticipantContextPort, never()).issue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("브라우저 사용자가 고른 참여자가 같은 정산에 있으면 본인 검증 없이 문맥을 발급한다")
    void selectsTrustedBrowserParticipant() {
        arrangeDutchPayAccessWithoutParticipants();
        SettlementParticipant payer = participants().get(1);
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID, PAYER_PARTICIPANT_ID
        )).thenReturn(Optional.of(payer));
        arrangeContext(PAYER_PARTICIPANT_ID);

        var result = service.select(RAW_TOKEN, PAYER_PARTICIPANT_ID);

        assertThat(result.participant().displayName()).isEqualTo("민선영");
        assertThat(result.participant().isRequester()).isFalse();
        assertThat(result.participant().amount()).isEqualTo(6_000L);
        assertThat(result.nextPhase()).isEqualTo(SettlementRequestPhase.TRANSFER_READY);
        assertThat(result.participantContext()).isEqualTo(CONTEXT_ID.toString());
    }

    @Test
    @DisplayName("완료된 정산의 링크는 활성 상태여도 공개하지 않는다")
    void rejectsCompletedSettlementLink() {
        arrangeLink();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(
                settlement(SettlementAllocationType.DUTCH_PAY, SettlementStatus.COMPLETED)
        ));

        assertThatThrownBy(() -> service.resolve(RAW_TOKEN))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND));
        verify(loadSettlementParticipantPort, never()).findAllBySettlementId(any());
    }

    @Test
    @DisplayName("전원 제출로 중단된 뒤에도 링크로 송금 안내를 다시 볼 수 있다")
    void keepsLinkOpenAfterEverySubmission() {
        arrangeLink();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(
                settlement(SettlementAllocationType.ITEM_ALLOCATION, SettlementStatus.SUSPENDED)
        ));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.empty());

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.allocationPhase()).isEqualTo(SettlementRequestPhase.TRANSFER_READY);
        assertThat(result.participantCandidates()).hasSize(2);
    }

    @Test
    @DisplayName("중단 후 재개해 REQUESTED로 돌아온 더치페이도 송금 안내로 연다")
    void opensResumedDutchPayAsTransferReady() {
        arrangeLink();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(
                settlement(SettlementAllocationType.DUTCH_PAY, SettlementStatus.REQUESTED)
        ));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(shares());
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.empty());

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.allocationPhase()).isEqualTo(SettlementRequestPhase.TRANSFER_READY);
    }

    @Test
    @DisplayName("아직 요청 전인 초안은 링크로 열 수 없다")
    void rejectsDraftSettlement() {
        arrangeLink();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(
                settlement(SettlementAllocationType.ITEM_ALLOCATION, SettlementStatus.DRAFT)
        ));

        assertThatThrownBy(() -> service.resolve(RAW_TOKEN))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND));
    }

    @Test
    @DisplayName("영수증 정산이면 상호명과 결제일을 함께 준다")
    void exposesReceiptStoreNameAndPaidAt() {
        arrangeItemAllocationAccess();
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(
                List.of(SettlementExpense.receiptOcr(
                        SETTLEMENT_ID,
                        "ocr-1",
                        "행복식당",
                        10_000,
                        PAID_AT,
                        0,
                        CREATED_AT
                ))
        );
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.empty());

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.storeName()).isEqualTo("행복식당");
        assertThat(result.paidAt()).isEqualTo(PAID_AT);
    }

    @Test
    @DisplayName("결제내역 정산이면 상호명과 결제일을 함께 준다")
    void exposesPaymentHistoryStoreNameAndPaidAt() {
        arrangeItemAllocationAccess();
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(
                List.of(SettlementExpense.paymentHistory(
                        SETTLEMENT_ID,
                        1L,
                        "ocr-1",
                        "행복식당",
                        10_000,
                        PAID_AT,
                        0,
                        CREATED_AT
                ))
        );
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.empty());

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.storeName()).isEqualTo("행복식당");
        assertThat(result.paidAt()).isEqualTo(PAID_AT);
    }

    @Test
    @DisplayName("링크 생성 시각이 없으면 정산 생성 시각을 요청 시각으로 사용한다")
    void fallsBackToSettlementCreatedAtWhenLinkCreatedAtIsMissing() {
        arrangeItemAllocationAccess();
        SettlementLink link = mock(SettlementLink.class);
        when(link.isUsableAt(any())).thenReturn(true);
        when(link.settlementId()).thenReturn(SETTLEMENT_ID);
        when(link.createdAt()).thenReturn(null);
        when(loadSettlementLinkPort.findByTokenHash(TOKEN_HASH)).thenReturn(Optional.of(link));
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.empty());

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.requestedAt()).isEqualTo(SETTLEMENT_CREATED_AT);
    }

    @Test
    @DisplayName("영수증이 두 건 이상이면 어느 가게인지 정할 수 없어 비운다")
    void leavesReceiptInfoEmptyWhenAmbiguous() {
        arrangeItemAllocationAccess();
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(
                List.of(
                        SettlementExpense.receiptOcr(
                                SETTLEMENT_ID, "ocr-1", "행복식당", 6_000, PAID_AT, 0, CREATED_AT
                        ),
                        SettlementExpense.receiptOcr(
                                SETTLEMENT_ID, "ocr-2", "카페", 4_000, PAID_AT, 1, CREATED_AT
                        )
                )
        );
        when(currentActorPort.findCurrentActor()).thenReturn(Optional.empty());

        var result = service.resolve(RAW_TOKEN);

        assertThat(result.storeName()).isNull();
        assertThat(result.paidAt()).isNull();
    }

    private void arrangeDutchPayAccess() {
        arrangeDutchPayAccessWithoutParticipants();
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
    }

    private void arrangeDutchPayAccessWithoutParticipants() {
        arrangeLink();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(
                settlement(SettlementAllocationType.DUTCH_PAY, SettlementStatus.AWAITING_DEPOSIT)
        ));
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(shares());
    }

    private void arrangeItemAllocationAccess() {
        arrangeLink();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(
                settlement(SettlementAllocationType.ITEM_ALLOCATION, SettlementStatus.REQUESTED)
        ));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
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

    private void arrangeContext(UUID participantId) {
        when(issueParticipantContextPort.issue(
                eq(TOKEN_HASH), eq(SETTLEMENT_ID), eq(participantId), any()
        )).thenAnswer(invocation -> {
            LocalDateTime now = invocation.getArgument(3);
            return new SettlementParticipantContext(
                    CONTEXT_ID,
                    TOKEN_HASH,
                    SETTLEMENT_ID,
                    participantId,
                    now,
                    now.plusMinutes(30)
            );
        });
    }

    private Settlement settlement(
            SettlementAllocationType allocationType,
            SettlementStatus status
    ) {
        return new Settlement(
                SETTLEMENT_ID,
                1L,
                "회식 정산",
                10_000,
                allocationType,
                7L,
                status,
                SETTLEMENT_CREATED_AT,
                CREATED_AT
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                participant(CREATOR_PARTICIPANT_ID, 1L, "최수아", 0),
                participant(PAYER_PARTICIPANT_ID, 2L, "민선영", 1)
        );
    }

    private List<SettlementShare> shares() {
        return List.of(
                new SettlementShare(
                        UUID.randomUUID(),
                        SETTLEMENT_ID,
                        CREATOR_PARTICIPANT_ID,
                        4_000,
                        CREATED_AT
                ),
                new SettlementShare(
                        UUID.randomUUID(),
                        SETTLEMENT_ID,
                        PAYER_PARTICIPANT_ID,
                        6_000,
                        CREATED_AT
                )
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
}
