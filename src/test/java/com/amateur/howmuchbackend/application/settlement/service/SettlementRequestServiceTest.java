package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.settlement.netting.service.NettingEstimateInvalidationService;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.group.model.GroupMemberRoster;
import com.amateur.howmuchbackend.application.port.out.group.LockGroupMemberRosterPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementRequestPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementCategoryJobStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.application.notification.service.NotificationRecorder;
import com.amateur.howmuchbackend.domain.notification.UserNotification;
import com.amateur.howmuchbackend.domain.notification.NotificationType;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementCategoryClassificationPolicy;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementRequestPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementShare;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 요청 확정 Application 흐름")
class SettlementRequestServiceTest {
    @org.mockito.Mock private NettingEstimateInvalidationService estimateInvalidation;
    private static final Long USER_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString("bb97ee76-3a82-4ccc-b324-4a9974c8e1af");
    private static final UUID PAYER_PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 24, 10, 0);
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.now(ZoneOffset.UTC).plusDays(30);
    private static final String RAW_LINK_TOKEN = "r".repeat(43);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private LoadSettlementItemPort loadSettlementItemPort;
    @Mock private SettlementDutchPayAllocationStorePort allocationStorePort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private SettlementLinkTokenPort linkTokenPort;
    @Mock private SaveSettlementRequestPort saveSettlementRequestPort;
    @Mock private LockGroupMemberRosterPort lockGroupMemberRosterPort;
    @Mock private NotificationRecorder notificationRecorder;
    @Mock private SettlementCategoryJobStorePort categoryJobStorePort;

    private SettlementRequestService service;

    @BeforeEach
    void setUp() {
        service = new SettlementRequestService(
                currentActorPort,
                loadSettlementPort,
                saveSettlementPort,
                loadSettlementParticipantPort,
                loadSettlementExpensePort,
                loadSettlementItemPort,
                allocationStorePort,
                loadAccountPort,
                loadUserPort,
                new SettlementRequestPolicy(),
                linkTokenPort,
                saveSettlementRequestPort,
                lockGroupMemberRosterPort,
                notificationRecorder,
                categoryJobStorePort,
                new SettlementCategoryClassificationPolicy(),
                estimateInvalidation
        );
    }

    @Test
    @DisplayName("더치페이는 Share와 계좌 스냅샷을 고정하고 입금 대기 상태로 전환한다")
    void settlementRequest_dutchPay_freezesSharesAndAccountSnapshot() {
        arrangeCommon(SettlementAllocationType.DUTCH_PAY);
        when(allocationStorePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                allocation(CREATOR_PARTICIPANT_ID, 4_000),
                allocation(PAYER_PARTICIPANT_ID, 6_000)
        ));

        var result = service.request(SETTLEMENT_ID);

        ArgumentCaptor<Settlement> settlementCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).update(settlementCaptor.capture());
        assertThat(settlementCaptor.getValue().status())
                .isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
        assertThat(result.allocationPhase()).isEqualTo(SettlementRequestPhase.TRANSFER_READY);
        assertThat(result.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);

        ArgumentCaptor<SettlementReceivingAccountSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(SettlementReceivingAccountSnapshot.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementShare>> sharesCaptor = ArgumentCaptor.forClass(List.class);
        verify(saveSettlementRequestPort).save(
                snapshotCaptor.capture(), sharesCaptor.capture(), any(),
                eq(RAW_LINK_TOKEN)
        );
        assertThat(snapshotCaptor.getValue().accountNumber()).isEqualTo("1002123456789");
        assertThat(snapshotCaptor.getValue().accountHolder()).isEqualTo("최수아");
        assertThat(sharesCaptor.getValue()).extracting(SettlementShare::amount)
                .containsExactly(4_000L, 6_000L);

        ArgumentCaptor<UserNotification> notification = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationRecorder).record(notification.capture());
        assertThat(notification.getValue().settlementId()).isEqualTo(SETTLEMENT_ID);
        assertThat(notification.getValue().recipientUserId()).isEqualTo(2L);
        assertThat(notification.getValue().type()).isEqualTo(NotificationType.SETTLEMENT_REQUEST);
        assertThat(notification.getValue().message()).isEqualTo("최수아님이 정산을 요청했어요");

        verify(estimateInvalidation).settlementChanged(SETTLEMENT_ID);

    }

    @Test
    @DisplayName("영수증·결제 내역 지출이 있으면 분류 대기 작업을 함께 저장한다")
    void settlementRequest_receiptExpense_schedulesCategoryJob() {
        arrangeCommon(SettlementAllocationType.DUTCH_PAY);
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementExpense(UUID.randomUUID(), SETTLEMENT_ID, SettlementExpenseSource.RECEIPT_OCR,
                        "receipt-1", null, "스타벅스 강남점", 10_000, 0, CREATED_AT, CREATED_AT)));
        when(allocationStorePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                allocation(CREATOR_PARTICIPANT_ID, 4_000),
                allocation(PAYER_PARTICIPANT_ID, 6_000)
        ));

        service.request(SETTLEMENT_ID);

        verify(categoryJobStorePort).schedule(eq(SETTLEMENT_ID), any());
        verify(categoryJobStorePort, never()).saveUncategorized(any(), any());
    }

    @Test
    @DisplayName("직접 입력 지출만 있으면 분류 근거가 없어 바로 미분류로 저장한다")
    void settlementRequest_manualOnly_savesUncategorized() {
        arrangeCommon(SettlementAllocationType.DUTCH_PAY);
        when(allocationStorePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                allocation(CREATOR_PARTICIPANT_ID, 4_000),
                allocation(PAYER_PARTICIPANT_ID, 6_000)
        ));

        service.request(SETTLEMENT_ID);

        verify(categoryJobStorePort).saveUncategorized(eq(SETTLEMENT_ID), any());
        verify(categoryJobStorePort, never()).schedule(any(), any());
    }

    @Test
    @DisplayName("품목별 배분은 Share 없이 계좌와 링크를 고정하고 참여자 선택 상태로 전환한다")
    void settlementRequest_itemAllocation_freezesAccountLinkNoShare() {
        arrangeCommon(SettlementAllocationType.ITEM_ALLOCATION);
        UUID expenseId = expenses().getFirst().id();
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                SettlementItem.fromReceipt(
                        SETTLEMENT_ID, expenseId, "식사", 1, 7_000, 0, CREATED_AT
                ),
                SettlementItem.fromReceipt(
                        SETTLEMENT_ID, expenseId, "음료", 1, 3_000, 1, CREATED_AT
                )
        ));

        var result = service.request(SETTLEMENT_ID);

        ArgumentCaptor<Settlement> settlementCaptor = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).update(settlementCaptor.capture());
        assertThat(settlementCaptor.getValue().status()).isEqualTo(SettlementStatus.REQUESTED);
        assertThat(result.allocationPhase()).isEqualTo(SettlementRequestPhase.ITEM_SELECTION);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementShare>> sharesCaptor = ArgumentCaptor.forClass(List.class);
        verify(saveSettlementRequestPort).save(
                any(), sharesCaptor.capture(), any(),
                eq(RAW_LINK_TOKEN)
        );
        assertThat(sharesCaptor.getValue()).isEmpty();
        verify(allocationStorePort, never()).findAllBySettlementId(any());
    }

    @Test
    @DisplayName("수령 계좌가 없으면 토큰과 요청 데이터를 만들기 전에 거부한다")
    void settlementRequest_missingAccount_rejectedEarly() {
        authenticate();
        Settlement settlement = new Settlement(
                SETTLEMENT_ID,
                USER_ID,
                "회식 정산",
                10_000,
                SettlementAllocationType.DUTCH_PAY,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));

        assertThatThrownBy(() -> service.request(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_REQUEST_RECEIVING_ACCOUNT_REQUIRED
                        ));
        verify(linkTokenPort, never()).issue(any());
        verify(saveSettlementRequestPort, never()).save(any(), any(), any(), any());
    }

    @Test
    @DisplayName("정산 요청 시 탈퇴한 그룹 출처 참여자가 있으면 발행을 거부한다")
    void settlementRequest_inactiveGroupMember_rejected() {
        authenticate();
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementAllocationType.DUTCH_PAY)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(
                        participant(CREATOR_PARTICIPANT_ID, USER_ID, "최수아", 0),
                        groupParticipant(PAYER_PARTICIPANT_ID, 10L, 2L, "민선영", 1)
                ));
        when(lockGroupMemberRosterPort.lockAndLoad(10L))
                .thenReturn(Optional.of(new GroupMemberRoster(10L, List.of(USER_ID))));

        assertThatThrownBy(() -> service.request(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.SETTLEMENT_PARTICIPANT_CANDIDATE_NOT_FOUND
                        ));

        verify(lockGroupMemberRosterPort).lockAndLoad(10L);
        verify(saveSettlementPort, never()).update(any());
        verify(linkTokenPort, never()).issue(any());
    }

    private void arrangeCommon(SettlementAllocationType allocationType) {
        authenticate();
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(allocationType)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(expenses());
        when(loadAccountPort.findActiveByIdAndUserId(7L, USER_ID))
                .thenReturn(Optional.of(account()));
        when(loadUserPort.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(loadUserPort.findAllByIds(List.of(2L))).thenReturn(List.of(payerUser()));
        when(linkTokenPort.issue(any())).thenReturn(
                new SettlementLinkTokenPort.IssuedSettlementLinkToken(
                        "a".repeat(64),
                        RAW_LINK_TOKEN,
                        "https://howmuch.example/settlements/token",
                        EXPIRES_AT
                )
        );
        when(saveSettlementPort.update(any(Settlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void authenticate() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
    }

    private Settlement settlement(SettlementAllocationType allocationType) {
        return new Settlement(
                SETTLEMENT_ID,
                USER_ID,
                "회식 정산",
                10_000,
                allocationType,
                7L,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                participant(CREATOR_PARTICIPANT_ID, USER_ID, "최수아", 0),
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

    private SettlementParticipant groupParticipant(
            UUID id,
            Long groupId,
            Long linkedUserId,
            String displayName,
            int displayOrder
    ) {
        return new SettlementParticipant(
                id,
                SETTLEMENT_ID,
                SettlementParticipantSource.GROUP_MEMBER,
                linkedUserId,
                null,
                groupId,
                displayName,
                displayOrder,
                CREATED_AT,
                CREATED_AT
        );
    }

    private List<SettlementExpense> expenses() {
        return List.of(SettlementExpense.manual(
                SETTLEMENT_ID, "회식", 10_000, 0, CREATED_AT
        ));
    }

    private SettlementDutchPayAllocation allocation(UUID participantId, long amount) {
        return SettlementDutchPayAllocation.create(
                SETTLEMENT_ID, participantId, amount, CREATED_AT
        );
    }

    private Account account() {
        return new Account(
                7L,
                USER_ID,
                5L,
                "0020",
                "candidate-7",
                "1002123456789",
                AccountType.DEPOSIT_TRUST,
                "생활비 통장",
                "****6789",
                new BigDecimal("1000000"),
                "KRW",
                true,
                "11",
                true,
                0
        );
    }

    private User user() {
        return new User(
                USER_ID,
                "identity-key",
                "최수아",
                "sua_user",
                null,
                null,
                "01012345678",
                "pin-hash",
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }

    private User payerUser() {
        return new User(
                2L,
                "payer-identity-key",
                "민선영",
                "payer_user",
                null,
                null,
                "01087654321",
                "pin-hash",
                null,
                null,
                true,
                UserRole.USER,
                true,
                null,
                CREATED_AT,
                null,
                CREATED_AT,
                CREATED_AT
        );
    }
}
