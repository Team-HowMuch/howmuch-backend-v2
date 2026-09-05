package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 상세 납부 현황 조회")
class SettlementDetailServiceTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("243a9c71-bf82-4678-87fa-f752d824ba37");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final UUID CONTACT_PARTICIPANT_ID = UUID.fromString("f2878f10-77cc-4e85-94a5-343b414f69d7");
    private static final UUID PAYER_SHARE_ID = UUID.fromString("063338bb-bc17-471a-b917-723f37788857");
    private static final UUID CONTACT_SHARE_ID = UUID.fromString("7819caf2-710a-4f80-9d5d-8abe7fbf20a9");
    private static final Long CREATOR_ID = 1L;
    private static final Long PAYER_ID = 2L;
    private static final Long OTHER_USER_ID = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 0, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private LoadSettlementReceivingAccountSnapshotPort loadReceivingAccountSnapshotPort;
    @Mock private LoadSettlementDepositSummaryPort loadDepositSummaryPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private SettlementReminderCooldownReader cooldownReader;
    @Mock private BankCatalog bankCatalog;

    private SettlementDetailService service;

    @BeforeEach
    void setUp() {
        service = new SettlementDetailService(
                currentActorPort,
                loadSettlementPort,
                loadSettlementExpensePort,
                loadSettlementParticipantPort,
                loadSettlementSharePort,
                loadReceivingAccountSnapshotPort,
                loadDepositSummaryPort,
                loadUserPort,
                cooldownReader,
                bankCatalog
        );
    }

    @Test
    @DisplayName("생성자에게 전체 납부자와 실제 입금액 기준 진행률을 반환한다")
    void returnsAllPaymentStatusesToCreator() {
        givenSettlement(CREATOR_ID);

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.viewerRole()).isEqualTo(SettlementDetailResult.ViewerRole.CREATOR);
        assertThat(result.creatorName()).isEqualTo("생성자");
        assertThat(result.bankName()).isEqualTo("NH농협");
        assertThat(result.accountNumber()).isEqualTo("3510018989873");
        assertThat(result.institutionIconUrl()).isEqualTo("https://cdn.example.com/nh.png");
        assertThat(result.paymentInfo()).isEqualTo(
                new SettlementDetailResult.PaymentInfo(1_400, null, null)
        );
        assertThat(result.paymentSummary()).isEqualTo(
                new SettlementDetailResult.PaymentSummary(1_000, 700, 300, 70, 2, 2, 1)
        );
        assertThat(result.reminderNextAvailableAt()).isNull();
        assertThat(result.participants()).extracting(
                SettlementDetailResult.ParticipantPayment::displayName,
                SettlementDetailResult.ParticipantPayment::paymentStatus
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                        "참여자",
                        SettlementDetailResult.PaymentStatus.PARTIALLY_PAID
                ),
                org.assertj.core.groups.Tuple.tuple(
                        "연락처",
                        SettlementDetailResult.PaymentStatus.PAID
                )
        );
    }

    @Test
    @DisplayName("생성자 상세에 연결된 미납 참여자의 다음 독촉 가능 시각을 반환한다")
    void restoresReminderCooldownForCreator() {
        givenSettlement(CREATOR_ID, List.of());
        LocalDateTime nextAvailableAt = NOW.plusMinutes(30);
        when(cooldownReader.findLatestNextAvailableAt(
                eq(SETTLEMENT_ID),
                eq(List.of(PAYER_PARTICIPANT_ID))
        )).thenReturn(Optional.of(nextAvailableAt));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.reminderNextAvailableAt()).isEqualTo(nextAvailableAt);
    }

    @Test
    @DisplayName("단일 영수증 정산의 총액과 상호명과 결제일을 반환한다")
    void returnsReceiptPaymentInfo() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 25, 19, 30);
        givenSettlement(CREATOR_ID);
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(SettlementExpense.receiptOcr(
                        SETTLEMENT_ID,
                        "receipt-app-1",
                        "롯데리아",
                        1_400,
                        paidAt,
                        0,
                        NOW
                )));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.paymentInfo()).isEqualTo(
                new SettlementDetailResult.PaymentInfo(1_400, "롯데리아", paidAt)
        );
    }

    @Test
    @DisplayName("연결 참여자에게 전체 요약과 자신의 납부 행만 반환한다")
    void limitsParticipantDetailsToSelf() {
        givenSettlement(PAYER_ID);

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.viewerRole()).isEqualTo(SettlementDetailResult.ViewerRole.PARTICIPANT);
        assertThat(result.reminderNextAvailableAt()).isNull();
        assertThat(result.paymentSummary().progressPercent()).isEqualTo(70);
        assertThat(result.participants()).singleElement()
                .satisfies(payment -> {
                    assertThat(payment.participantId()).isEqualTo(PAYER_PARTICIPANT_ID);
                    assertThat(payment.confirmedAmount()).isEqualTo(300);
                });
        verifyNoInteractions(cooldownReader);
        verifyNoInteractions(loadReceivingAccountSnapshotPort, bankCatalog);
    }

    @Test
    @DisplayName("생성자 초안은 수금 계좌 정보를 반환하지 않는다")
    void returnsNullReceivingAccountForDraft() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlementDraft()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.bankName()).isNull();
        assertThat(result.accountNumber()).isNull();
        assertThat(result.institutionIconUrl()).isNull();
        verifyNoInteractions(loadReceivingAccountSnapshotPort, bankCatalog);
    }

    @Test
    @DisplayName("진행 중 정산의 수금 계좌 스냅샷이 없으면 404를 반환한다")
    void rejectsMissingReceivingAccountSnapshot() {
        givenSettlement(CREATOR_ID);
        lenient().when(loadReceivingAccountSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("Share가 없는 참여자는 금액을 추측하지 않고 NOT_READY로 반환한다")
    void returnsNotReadyBeforeShareCreation() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        givenReceivingAccount();
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.paymentSummary().expectedAmount()).isZero();
        assertThat(result.paymentSummary().progressPercent()).isZero();
        assertThat(result.participants()).allSatisfy(payment -> {
            assertThat(payment.expectedAmount()).isNull();
            assertThat(payment.paymentStatus())
                    .isEqualTo(SettlementDetailResult.PaymentStatus.NOT_READY);
        });
    }

    @Test
    @DisplayName("생성자도 연결 참여자도 아닌 사용자의 조회를 거부한다")
    void rejectsUnrelatedUser() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());

        assertThatThrownBy(() -> service.get(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verifyNoInteractions(
                loadSettlementExpensePort,
                loadSettlementSharePort,
                loadDepositSummaryPort
        );
    }

    @Test
    @DisplayName("생성자 참여자 백필 전 정산도 사용자 이름으로 상세 조회한다")
    void supportsSettlementWithoutCreatorParticipant() {
        User creator = mock(User.class);
        when(creator.name()).thenReturn("기존 생성자");
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(participants().get(1)));
        when(loadUserPort.findById(CREATOR_ID)).thenReturn(Optional.of(creator));
        givenReceivingAccount();
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.creatorName()).isEqualTo("기존 생성자");
        assertThat(result.participants()).singleElement()
                .satisfies(payment -> assertThat(payment.participantId())
                        .isEqualTo(PAYER_PARTICIPANT_ID));
    }

    private void givenSettlement(Long actorId) {
        givenSettlement(
                actorId,
                List.of(
                        new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(
                                PAYER_SHARE_ID,
                                300
                        ),
                        new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(
                                CONTACT_SHARE_ID,
                                400
                        )
                )
        );
    }

    private void givenSettlement(
            Long actorId,
            List<LoadSettlementDepositSummaryPort.ConfirmedShareAmount> confirmedAmounts
    ) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(actorId));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(
                        new SettlementShare(PAYER_SHARE_ID, SETTLEMENT_ID, PAYER_PARTICIPANT_ID, 600, NOW),
                        new SettlementShare(CONTACT_SHARE_ID, SETTLEMENT_ID, CONTACT_PARTICIPANT_ID, 400, NOW)
                ));
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(confirmedAmounts);
        givenReceivingAccount();
    }

    private void givenReceivingAccount() {
        lenient().when(loadReceivingAccountSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.of(new SettlementReceivingAccountSnapshot(
                        SETTLEMENT_ID,
                        10L,
                        "0020",
                        "3510018989873",
                        "생성자",
                        NOW
                )));
        lenient().when(bankCatalog.getBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "NH농협", "https://cdn.example.com/nh.png")
        ));
    }

    private Settlement settlementDraft() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                1_400,
                SettlementAllocationType.DUTCH_PAY,
                null,
                SettlementStatus.DRAFT,
                NOW,
                NOW
        );
    }

    private Settlement settlement() {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                1_400,
                SettlementAllocationType.DUTCH_PAY,
                10L,
                SettlementStatus.AWAITING_DEPOSIT,
                NOW,
                NOW
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                new SettlementParticipant(
                        CREATOR_PARTICIPANT_ID, SETTLEMENT_ID,
                        SettlementParticipantSource.REGISTERED_USER, CREATOR_ID,
                        null, null, "생성자", 0, NOW, NOW
                ),
                new SettlementParticipant(
                        PAYER_PARTICIPANT_ID, SETTLEMENT_ID,
                        SettlementParticipantSource.REGISTERED_USER, PAYER_ID,
                        null, null, "참여자", 1, NOW, NOW
                ),
                new SettlementParticipant(
                        CONTACT_PARTICIPANT_ID, SETTLEMENT_ID,
                        SettlementParticipantSource.CONTACT, null,
                        "a".repeat(64), null, "연락처", 2, NOW, NOW
                )
        );
    }
}
