package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.image.ImageCacheVersion;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementDepositSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementSharePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSelection;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemSubmission;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
    private static final UUID EXPENSE_ID = UUID.fromString("eaaac071-e994-496f-9588-aa96d0e2a12c");
    private static final UUID CREATOR_ITEM_ID = UUID.fromString("ad5773c9-e649-49c7-95c0-fc90d3dbaec1");
    private static final UUID PAYER_ITEM_ID = UUID.fromString("4a539ff2-ee38-4f98-9d11-cb57d13e44b2");
    private static final Long CREATOR_ID = 1L;
    private static final Long PAYER_ID = 2L;
    private static final Long OTHER_USER_ID = 3L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 0, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private LoadSettlementItemPort loadSettlementItemPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementSharePort loadSettlementSharePort;
    @Mock private SettlementItemAllocationStorePort itemAllocationStorePort;
    @Mock private LoadSettlementReceivingAccountSnapshotPort loadReceivingAccountSnapshotPort;
    @Mock private LoadSettlementDepositSummaryPort loadDepositSummaryPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private SettlementReminderCooldownReader cooldownReader;
    @Mock private BankCatalog bankCatalog;

    private SettlementDetailService service;
    private SettlementItemAllocationPolicy itemAllocationPolicy;

    @BeforeEach
    void setUp() {
        itemAllocationPolicy = new SettlementItemAllocationPolicy();
        service = new SettlementDetailService(
                currentActorPort,
                loadSettlementPort,
                loadSettlementExpensePort,
                loadSettlementItemPort,
                loadSettlementParticipantPort,
                loadSettlementSharePort,
                itemAllocationStorePort,
                loadReceivingAccountSnapshotPort,
                loadDepositSummaryPort,
                loadUserPort,
                cooldownReader,
                bankCatalog,
                itemAllocationPolicy
        );
    }

    @Test
    @DisplayName("생성자에게 전체 납부자와 실제 입금액 기준 진행률을 반환한다")
    void settlementDetail_creatorView_returnsStatusesAndProgress() {
        givenSettlement(CREATOR_ID);

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.endedAt()).isNull();
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
    void settlementDetail_suspended_usesSuspensionTimeAsEndedAt() {
        givenSettlement(PAYER_ID);
        Settlement suspended = settlement(SettlementStatus.SUSPENDED);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(suspended));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.endedAt()).isEqualTo(suspended.updatedAt());
    }

    @Test
    void settlementDetail_completed_usesCompletionTimeAsEndedAt() {
        givenSettlement(PAYER_ID);
        Settlement completed = settlement(SettlementStatus.COMPLETED);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(completed));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.endedAt()).isEqualTo(completed.updatedAt());
    }

    @Test
    @DisplayName("생성자 상세에 연결된 미납 참여자의 다음 독촉 가능 시각을 반환한다")
    void settlementDetail_creatorView_restoresReminderCooldown() {
        givenSettlement(CREATOR_ID, List.of());
        LocalDateTime nextAvailableAt = NOW.plusMinutes(30);
        when(cooldownReader.findLatestNextAvailableAt(
                eq(SETTLEMENT_ID),
                eq(List.of(PAYER_PARTICIPANT_ID))
        )).thenReturn(Optional.of(nextAvailableAt));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.endedAt()).isNull();
        assertThat(result.reminderNextAvailableAt()).isEqualTo(nextAvailableAt);
    }

    @Test
    @DisplayName("품목 선택 대기 상세는 Share가 없는 연결 참여자의 다음 독촉 가능 시각을 반환한다")
    void settlementDetail_itemSelectionWithoutShare_restoresCooldown() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(itemSettlement(SettlementStatus.REQUESTED)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());
        when(itemAllocationStorePort.findAllSubmissions(SETTLEMENT_ID))
                .thenReturn(List.of());
        givenReceivingAccount();
        LocalDateTime nextAvailableAt = NOW.plusMinutes(30);
        when(cooldownReader.findLatestNextAvailableAt(
                eq(SETTLEMENT_ID),
                eq(List.of(PAYER_PARTICIPANT_ID))
        )).thenReturn(Optional.of(nextAvailableAt));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.reminderNextAvailableAt()).isEqualTo(nextAvailableAt);
        assertThat(result.participants()).extracting(
                SettlementDetailResult.ParticipantPayment::participantId,
                SettlementDetailResult.ParticipantPayment::paymentStatus
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                        PAYER_PARTICIPANT_ID,
                        SettlementDetailResult.PaymentStatus.NOT_READY
                ),
                org.assertj.core.groups.Tuple.tuple(
                        CONTACT_PARTICIPANT_ID,
                        SettlementDetailResult.PaymentStatus.NOT_READY
                )
        );
    }

    @Test
    @DisplayName("재개 후 Share가 남아 있어도 미제출 참여자의 다음 독촉 가능 시각을 반환한다")
    void settlementDetail_resume_restoresPendingCooldown() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(itemSettlement(SettlementStatus.REQUESTED)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementShare(
                        PAYER_SHARE_ID,
                        SETTLEMENT_ID,
                        PAYER_PARTICIPANT_ID,
                        600,
                        NOW
                )
        ));
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());
        when(itemAllocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of());
        givenReceivingAccount();
        LocalDateTime nextAvailableAt = NOW.plusMinutes(30);
        when(cooldownReader.findLatestNextAvailableAt(
                eq(SETTLEMENT_ID),
                eq(List.of(PAYER_PARTICIPANT_ID))
        )).thenReturn(Optional.of(nextAvailableAt));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.endedAt()).isNull();
        assertThat(result.reminderNextAvailableAt()).isEqualTo(nextAvailableAt);
        assertThat(result.participants()).first().satisfies(payment ->
                assertThat(payment.paymentStatus())
                        .isEqualTo(SettlementDetailResult.PaymentStatus.UNPAID)
        );
    }

    @Test
    @DisplayName("Share가 없어도 품목 선택을 제출한 참여자는 독촉 쿨다운 복원 대상에서 제외한다")
    void settlementReminder_submittedWithoutShare_noCooldown() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(itemSettlement(SettlementStatus.REQUESTED)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());
        when(itemAllocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItemSubmission.submit(SETTLEMENT_ID, PAYER_PARTICIPANT_ID, NOW)
        ));
        givenReceivingAccount();

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.reminderNextAvailableAt()).isNull();
        org.mockito.Mockito.verify(cooldownReader)
                .findLatestNextAvailableAt(SETTLEMENT_ID, List.of());
    }

    @Test
    @DisplayName("단일 영수증 정산의 총액과 상호명과 결제일을 반환한다")
    void settlementReceipt_singleExpense_returnsStoreDateAndTotal() {
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
    @DisplayName("단일 결제내역 정산의 총액과 상호명과 결제일을 반환한다")
    void settlementHistory_singleImport_returnsStoreDateAndTotal() {
        LocalDateTime paidAt = LocalDateTime.of(2026, 8, 25, 19, 30);
        givenSettlement(CREATOR_ID);
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(SettlementExpense.paymentHistory(
                        SETTLEMENT_ID,
                        1L,
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
    void settlementDetail_linkedParticipant_seesOwnPaymentRow() {
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
    void settlementDraft_receivingAccount_returnsNull() {
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
    void settlementDetail_missingAccountSnapshot_returnsNotFound() {
        givenSettlement(CREATOR_ID);
        lenient().when(loadReceivingAccountSnapshotPort.findBySettlementId(SETTLEMENT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND));
    }

    @Test
    @DisplayName("선택 대기 중인 품목 정산은 Share가 없어도 총액을 미수금으로 반환한다")
    void settlementItemAllocation_noShares_usesTotalAsReceivable() {
        User payer = mock(User.class);
        when(payer.id()).thenReturn(PAYER_ID);
        when(payer.avatarFileName()).thenReturn("payer.png");
        when(loadUserPort.findAllByIds(java.util.Set.of(PAYER_ID))).thenReturn(List.of(payer));
        givenRequestedItemSettlement(List.of(), List.of());

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.participants().getFirst().avatarVersion()).isEqualTo(
                ImageCacheVersion.fromNullableFileName("payer.png")
        );
        assertThat(result.paymentSummary()).isEqualTo(
                new SettlementDetailResult.PaymentSummary(1_400, 0, 1_400, 0, 2, 2, 0)
        );
        assertThat(result.participants()).allSatisfy(payment -> {
            assertThat(payment.expectedAmount()).isNull();
            assertThat(payment.paymentStatus())
                    .isEqualTo(SettlementDetailResult.PaymentStatus.NOT_READY);
        });
    }

    @Test
    @DisplayName("선택 대기 중인 품목 정산은 일부 확인 입금만 총 미수금에서 뺀다")
    void settlementItemAllocation_partialDeposit_deductsPaidAmount() {
        givenRequestedItemSettlement(
                List.of(new SettlementShare(
                        PAYER_SHARE_ID,
                        SETTLEMENT_ID,
                        PAYER_PARTICIPANT_ID,
                        600,
                        NOW
                )),
                List.of(new LoadSettlementDepositSummaryPort.ConfirmedShareAmount(
                        PAYER_SHARE_ID,
                        300
                ))
        );

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.paymentSummary()).isEqualTo(
                new SettlementDetailResult.PaymentSummary(1_400, 300, 1_100, 21, 2, 2, 0)
        );
        assertThat(result.participants()).satisfiesExactly(
                payment -> {
                    assertThat(payment.expectedAmount()).isEqualTo(600);
                    assertThat(payment.confirmedAmount()).isEqualTo(300);
                    assertThat(payment.paymentStatus())
                            .isEqualTo(SettlementDetailResult.PaymentStatus.PARTIALLY_PAID);
                },
                payment -> {
                    assertThat(payment.expectedAmount()).isNull();
                    assertThat(payment.confirmedAmount()).isZero();
                    assertThat(payment.paymentStatus())
                            .isEqualTo(SettlementDetailResult.PaymentStatus.NOT_READY);
                }
        );
    }

    @Test
    @DisplayName("선택 대기 중인 품목 정산은 생성자가 고른 몫을 미수금에서 제외한다")
    void settlementItemAllocation_creatorShare_excludedFromReceivable() {
        givenRequestedItemSettlement(List.of(), List.of());
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                item(CREATOR_ITEM_ID, "생성자 품목", 400, 0),
                item(PAYER_ITEM_ID, "참여자 품목", 1_000, 1)
        ));
        when(itemAllocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItemSelection.create(
                        SETTLEMENT_ID,
                        CREATOR_ITEM_ID,
                        CREATOR_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.ONE,
                        1,
                        NOW
                )
        ));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.paymentSummary()).isEqualTo(
                new SettlementDetailResult.PaymentSummary(1_000, 0, 1_000, 0, 2, 2, 0)
        );
        assertThat(result.participants()).allSatisfy(payment -> {
            assertThat(payment.expectedAmount()).isNull();
            assertThat(payment.paymentStatus())
                    .isEqualTo(SettlementDetailResult.PaymentStatus.NOT_READY);
        });
    }

    @Test
    @DisplayName("품목 선택을 제출했지만 0원인 참여자는 납부 완료로 반환한다")
    void settlementItemAllocation_submittedZeroShare_returnsPaid() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(itemSettlement(SettlementStatus.AWAITING_DEPOSIT)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementShare(
                        PAYER_SHARE_ID,
                        SETTLEMENT_ID,
                        PAYER_PARTICIPANT_ID,
                        1_000,
                        NOW
                )
        ));
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());
        when(itemAllocationStorePort.findAllSubmissions(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItemSubmission.submit(SETTLEMENT_ID, CONTACT_PARTICIPANT_ID, NOW)
        ));
        givenReceivingAccount();

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        InOrder readOrder = inOrder(itemAllocationStorePort, loadSettlementSharePort);
        readOrder.verify(itemAllocationStorePort).findAllSubmissions(SETTLEMENT_ID);
        readOrder.verify(loadSettlementSharePort).findAllBySettlementId(SETTLEMENT_ID);
        assertThat(result.paymentSummary()).isEqualTo(
                new SettlementDetailResult.PaymentSummary(1_000, 0, 1_000, 0, 2, 2, 1)
        );
        assertThat(result.participants()).satisfiesExactly(
                payment -> {
                    assertThat(payment.expectedAmount()).isEqualTo(1_000);
                    assertThat(payment.paymentStatus())
                            .isEqualTo(SettlementDetailResult.PaymentStatus.UNPAID);
                },
                payment -> {
                    assertThat(payment.expectedAmount()).isEqualTo(0L);
                    assertThat(payment.confirmedAmount()).isZero();
                    assertThat(payment.paymentStatus())
                            .isEqualTo(SettlementDetailResult.PaymentStatus.PAID);
                }
        );
    }

    @Test
    @DisplayName("할인 배분에 실패해 중단된 정산은 전원 제출해도 납부 완료로 표시하지 않는다")
    void discountedItemAllocation_suspendedAfterAllSubmissions_remainsNotReady() {
        givenDiscountedItemSettlement(
                SettlementStatus.SUSPENDED,
                List.of(SettlementItemSelection.create(
                        SETTLEMENT_ID, PAYER_ITEM_ID, PAYER_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.HALF, 1, NOW
                )),
                List.of()
        );

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.participants()).allSatisfy(payment -> {
            assertThat(payment.expectedAmount()).isNull();
            assertThat(payment.paymentStatus())
                    .isEqualTo(SettlementDetailResult.PaymentStatus.NOT_READY);
        });
        assertThat(result.paymentSummary().completedParticipantCount()).isZero();
    }

    @Test
    @DisplayName("할인 배분이 확정된 정산의 0원 참여자는 납부 완료로 표시한다")
    void discountedItemAllocation_finalizedZeroShare_returnsPaid() {
        givenDiscountedItemSettlement(
                SettlementStatus.AWAITING_DEPOSIT,
                List.of(SettlementItemSelection.create(
                        SETTLEMENT_ID, PAYER_ITEM_ID, PAYER_PARTICIPANT_ID,
                        SettlementItemAllocationFraction.ONE, 1, NOW
                )),
                List.of(new SettlementShare(
                        PAYER_SHARE_ID, SETTLEMENT_ID, PAYER_PARTICIPANT_ID, 1_400, NOW
                ))
        );

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.participants().get(1).expectedAmount()).isZero();
        assertThat(result.participants().get(1).paymentStatus())
                .isEqualTo(SettlementDetailResult.PaymentStatus.PAID);
        assertThat(result.paymentSummary().completedParticipantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("품목 선택이 끝나면 기존처럼 납부 대상 Share 합계를 기준으로 계산한다")
    void settlementItemAllocation_completed_usesPayableShareTotal() {
        givenSettlement(CREATOR_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(itemSettlement(SettlementStatus.AWAITING_DEPOSIT)));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.paymentSummary()).isEqualTo(
                new SettlementDetailResult.PaymentSummary(1_000, 700, 300, 70, 2, 2, 1)
        );
    }

    @Test
    @DisplayName("생성자도 연결 참여자도 아닌 사용자의 조회를 거부한다")
    void settlementDetail_unrelatedUser_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());

        assertThatThrownBy(() -> service.get(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verifyNoInteractions(
                loadUserPort,
                loadSettlementExpensePort,
                loadSettlementSharePort,
                loadDepositSummaryPort
        );
    }

    @Test
    @DisplayName("생성자 참여자 백필 전 정산도 사용자 이름으로 상세 조회한다")
    void settlementDetail_missingCreatorParticipant_usesUserName() {
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

    @Test
    @DisplayName("표시 대상 사용자를 일괄 조회해 파일명 기반 사진 버전을 반환한다")
    void settlementDetail_linkedUsers_returnsAvatarVersions() {
        givenSettlement(CREATOR_ID);
        User payer = mock(User.class);
        when(payer.id()).thenReturn(PAYER_ID);
        when(payer.avatarFileName()).thenReturn("payer.png");
        when(loadUserPort.findAllByIds(java.util.Set.of(PAYER_ID))).thenReturn(List.of(payer));

        SettlementDetailResult result = service.get(SETTLEMENT_ID);

        assertThat(result.participants()).extracting(
                SettlementDetailResult.ParticipantPayment::avatarVersion
        ).containsExactly(
                ImageCacheVersion.fromNullableFileName("payer.png"),
                null
        );
        org.mockito.Mockito.verify(loadUserPort).findAllByIds(java.util.Set.of(PAYER_ID));
        org.mockito.Mockito.verifyNoMoreInteractions(loadUserPort);
    }

    @Test
    @DisplayName("프로필 사진이 없거나 사용자가 없으면 이미지 없음으로 반환한다")
    void settlementDetail_missingImageOrUser_returnsNoAvatar() {
        givenSettlement(CREATOR_ID);
        User payer = mock(User.class);
        when(loadUserPort.findAllByIds(java.util.Set.of(PAYER_ID)))
                .thenReturn(List.of(payer)).thenReturn(List.of());

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(service.get(SETTLEMENT_ID).participants())
                    .allSatisfy(payment -> assertThat(payment.avatarVersion()).isNull());
        }
    }

    @Test
    @DisplayName("참여자는 다른 연결 참여자의 사진 정보를 조회하지 않는다")
    void settlementDetail_linkedParticipant_loadsOwnAvatarOnly() {
        givenSettlement(PAYER_ID);
        var expandedParticipants = new java.util.ArrayList<>(participants());
        expandedParticipants.add(new SettlementParticipant(
                UUID.randomUUID(), SETTLEMENT_ID,
                SettlementParticipantSource.REGISTERED_USER, OTHER_USER_ID,
                null, null, "참여자", 3, NOW, NOW
        ));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(expandedParticipants);
        User payer = mock(User.class);
        when(payer.id()).thenReturn(PAYER_ID);
        when(payer.avatarFileName()).thenReturn("payer.png");
        when(loadUserPort.findAllByIds(java.util.Set.of(PAYER_ID))).thenReturn(List.of(payer));

        assertThat(service.get(SETTLEMENT_ID).participants()).singleElement()
                .satisfies(payment -> assertThat(payment.avatarVersion()).isEqualTo(
                        ImageCacheVersion.fromNullableFileName("payer.png")
                ));
        org.mockito.Mockito.verify(loadUserPort).findAllByIds(java.util.Set.of(PAYER_ID));
        org.mockito.Mockito.verifyNoMoreInteractions(loadUserPort);
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

    private void givenRequestedItemSettlement(
            List<SettlementShare> shares,
            List<LoadSettlementDepositSummaryPort.ConfirmedShareAmount> confirmedAmounts
    ) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(itemSettlement(SettlementStatus.REQUESTED)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(shares);
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(confirmedAmounts);
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of());
        when(itemAllocationStorePort.findAllSelections(SETTLEMENT_ID)).thenReturn(List.of());
        givenReceivingAccount();
    }

    private void givenDiscountedItemSettlement(
            SettlementStatus status,
            List<SettlementItemSelection> selections,
            List<SettlementShare> shares
    ) {
        SettlementExpense expense = SettlementExpense.receiptOcr(
                SETTLEMENT_ID, "receipt-1", "상호", 1_400, null, 0, NOW, 100
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(itemSettlement(status)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(expense));
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(new SettlementItem(
                        PAYER_ITEM_ID, SETTLEMENT_ID, expense.id(), "품목", 1,
                        1_500, 0, NOW, NOW
                )));
        when(itemAllocationStorePort.findAllSelections(SETTLEMENT_ID))
                .thenReturn(selections);
        when(itemAllocationStorePort.findAllSubmissions(SETTLEMENT_ID))
                .thenReturn(participants().stream().map(participant ->
                        SettlementItemSubmission.submit(SETTLEMENT_ID, participant.id(), NOW)
                ).toList());
        when(loadSettlementSharePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(shares);
        when(loadDepositSummaryPort.findConfirmedShareAmountsBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());
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
        lenient().when(bankCatalog.getAllBanks()).thenReturn(List.of(
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
        return settlement(SettlementStatus.AWAITING_DEPOSIT);
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                1_400,
                SettlementAllocationType.DUTCH_PAY,
                10L,
                status,
                NOW,
                NOW
        );
    }

    private Settlement itemSettlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                1_400,
                SettlementAllocationType.ITEM_ALLOCATION,
                10L,
                status,
                NOW,
                NOW
        );
    }

    private SettlementItem item(
            UUID itemId,
            String name,
            long amount,
            int displayOrder
    ) {
        return new SettlementItem(
                itemId,
                SETTLEMENT_ID,
                EXPENSE_ID,
                name,
                1,
                amount,
                displayOrder,
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
