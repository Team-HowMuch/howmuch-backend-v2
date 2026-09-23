package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementAllocationTypeCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDutchPayAllocationCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementAllocationNextStep;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementDutchPayAllocationStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.DutchPayAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementDutchPayAllocation;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementItem;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 배분 Application 흐름")
class SettlementAllocationServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final List<UUID> PARTICIPANT_IDS = List.of(
            UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa"),
            UUID.fromString("bbcc9e4e-70df-4a6c-a0e6-4f319de1f89a"),
            UUID.fromString("f2878f10-77cc-4e85-94a5-343b414f69d7")
    );
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 18, 0);

    @Mock
    private CurrentActorPort currentActorPort;
    @Mock
    private LoadSettlementPort loadSettlementPort;
    @Mock
    private SaveSettlementPort saveSettlementPort;
    @Mock
    private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock
    private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock
    private LoadSettlementItemPort loadSettlementItemPort;
    @Mock
    private SettlementDutchPayAllocationStorePort allocationStorePort;

    private SettlementAllocationService service;

    @BeforeEach
    void setUp() {
        service = new SettlementAllocationService(
                currentActorPort,
                loadSettlementPort,
                saveSettlementPort,
                loadSettlementParticipantPort,
                loadSettlementExpensePort,
                loadSettlementItemPort,
                allocationStorePort,
                new DutchPayAllocationPolicy()
        );
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
    }

    @Test
    @DisplayName("기준 총액과 지출 합계가 같으면 기본 N빵을 만들고 잔여금을 생성자에게 배분한다")
    void allocationType_balancedExpenses_createsDutchPayShares() {
        Settlement settlement = settlement(null, 1_000);
        List<SettlementParticipant> participants = participants();
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement));
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(expense(1_000)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants);
        when(saveSettlementPort.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationStorePort.replaceAll(eq(SETTLEMENT_ID), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        var result = service.select(
                SETTLEMENT_ID,
                new SelectSettlementAllocationTypeCommand(SettlementAllocationType.DUTCH_PAY)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementDutchPayAllocation>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(allocationStorePort).replaceAll(eq(SETTLEMENT_ID), captor.capture());
        assertThat(captor.getValue()).extracting(SettlementDutchPayAllocation::amount)
                .containsExactly(334L, 333L, 333L);
        assertThat(result.allocationType()).isEqualTo(SettlementAllocationType.DUTCH_PAY);
        assertThat(result.nextStep()).isEqualTo(SettlementAllocationNextStep.REVIEW_DUTCH_PAY);
    }

    @Test
    @DisplayName("네트워크 재시도로 같은 유형을 선택해도 사용자가 수정한 금액을 다시 N빵하지 않는다")
    void allocationType_idempotentSelection_preservesEditedAmount() {
        Settlement settlement = settlement(SettlementAllocationType.DUTCH_PAY, 1_000);
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement));
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(expense(1_000)));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants());
        when(allocationStorePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(allocations(334, 333, 333));

        service.select(
                SETTLEMENT_ID,
                new SelectSettlementAllocationTypeCommand(SettlementAllocationType.DUTCH_PAY)
        );

        verify(allocationStorePort, never()).replaceAll(eq(SETTLEMENT_ID), any());
        verify(saveSettlementPort, never()).update(any());
    }

    @Test
    @DisplayName("서로 다른 기준으로 배분하지 않도록 기준 총액과 지출 합계 불일치를 거부한다")
    void rejectsExpenseTotalMismatch() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(null, 1_000)));
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(expense(900)));

        assertThatThrownBy(() -> service.select(
                SETTLEMENT_ID,
                new SelectSettlementAllocationTypeCommand(SettlementAllocationType.DUTCH_PAY)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_EXPENSE_TOTAL_MISMATCH
                ));
        verify(saveSettlementPort, never()).update(any());
    }

    @Test
    @DisplayName("저장된 OCR 품목이 없으면 품목별 할당 선택을 거부한다")
    void rejectsItemAllocationWithoutReceiptItems() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(null, 1_000)));
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(expense(1_000)));
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.select(
                SETTLEMENT_ID,
                new SelectSettlementAllocationTypeCommand(
                        SettlementAllocationType.ITEM_ALLOCATION
                )
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_ITEM_REQUIRED
                ));
    }

    @Test
    @DisplayName("OCR 품목 합계가 전체 지출과 같으면 품목별 할당을 선택한다")
    void selectsItemAllocationWithBalancedReceiptItems() {
        Settlement settlement = settlement(null, 1_000);
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement));
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(expense(1_000)));
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(item(400, 0), item(600, 1)));
        when(saveSettlementPort.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.select(
                SETTLEMENT_ID,
                new SelectSettlementAllocationTypeCommand(
                        SettlementAllocationType.ITEM_ALLOCATION
                )
        );

        assertThat(result.allocationType()).isEqualTo(SettlementAllocationType.ITEM_ALLOCATION);
        assertThat(result.nextStep()).isEqualTo(
                SettlementAllocationNextStep.REVIEW_ITEM_ALLOCATION
        );
        verify(allocationStorePort).deleteAllBySettlementId(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("OCR 품목 합계가 전체 지출보다 작으면 품목별 할당 선택을 거부한다")
    void rejectsItemAllocationTotalMismatch() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(null, 1_000)));
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(expense(1_000)));
        when(loadSettlementItemPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(item(900, 0)));

        assertThatThrownBy(() -> service.select(
                SETTLEMENT_ID,
                new SelectSettlementAllocationTypeCommand(
                        SettlementAllocationType.ITEM_ALLOCATION
                )
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_ITEM_TOTAL_MISMATCH
                ));
        verify(saveSettlementPort, never()).update(any());
    }

    @Test
    @DisplayName("한 참여자 금액 수정 후 현재 초과액을 자동 보정하지 않고 그대로 반환한다")
    void updatesParticipantAmountAndReturnsDifference() {
        Settlement settlement = settlement(SettlementAllocationType.DUTCH_PAY, 1_000);
        List<SettlementParticipant> participants = participants();
        List<SettlementDutchPayAllocation> before = allocations(334, 333, 333);
        SettlementDutchPayAllocation updated = before.get(1).updateAmount(400, CREATED_AT);
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement));
        when(loadSettlementParticipantPort.findBySettlementIdAndId(
                SETTLEMENT_ID,
                participants.get(1).id()
        )).thenReturn(Optional.of(participants.get(1)));
        when(allocationStorePort.findBySettlementIdAndParticipantId(
                SETTLEMENT_ID,
                participants.get(1).id()
        )).thenReturn(Optional.of(before.get(1)));
        when(allocationStorePort.update(any())).thenReturn(updated);
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(participants);
        when(allocationStorePort.findAllBySettlementId(SETTLEMENT_ID))
                .thenReturn(List.of(before.get(0), updated, before.get(2)));

        var result = service.update(
                SETTLEMENT_ID,
                participants.get(1).id(),
                new UpdateSettlementDutchPayAllocationCommand(400)
        );

        assertThat(result.allocatedAmount()).isEqualTo(1_067);
        assertThat(result.differenceAmount()).isEqualTo(-67);
        assertThat(result.balanced()).isFalse();
        assertThat(result.participants()).extracting(item -> item.amount())
                .containsExactly(334L, 400L, 333L);
    }

    private Settlement settlement(SettlementAllocationType type, long totalAmount) {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                totalAmount,
                type,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                participant(CREATOR_ID, "생성자", 0),
                participant(2L, "친구 1", 1),
                participant(3L, "친구 2", 2)
        );
    }

    private SettlementParticipant participant(Long userId, String name, int order) {
        return new SettlementParticipant(
                PARTICIPANT_IDS.get(order),
                SETTLEMENT_ID,
                com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource.REGISTERED_USER,
                userId,
                null,
                null,
                name,
                order,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementExpense expense(long amount) {
        return SettlementExpense.manual(
                SETTLEMENT_ID,
                "지출",
                amount,
                0,
                CREATED_AT
        );
    }

    private SettlementItem item(long amount, int displayOrder) {
        return SettlementItem.fromReceipt(
                SETTLEMENT_ID,
                UUID.randomUUID(),
                "품목 " + displayOrder,
                1,
                amount,
                displayOrder,
                CREATED_AT
        );
    }

    private List<SettlementDutchPayAllocation> allocations(long... amounts) {
        List<SettlementParticipant> participants = participants();
        java.util.ArrayList<SettlementDutchPayAllocation> allocations =
                new java.util.ArrayList<>();
        for (int index = 0; index < amounts.length; index++) {
            allocations.add(SettlementDutchPayAllocation.create(
                    SETTLEMENT_ID,
                    participants.get(index).id(),
                    amounts[index],
                    CREATED_AT
            ));
        }
        return List.copyOf(allocations);
    }
}
