package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 지출 Application 흐름")
class SettlementExpenseServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final UUID EXPENSE_ID = UUID.fromString(
            "798cb149-f804-4427-9194-73ebdd7ff2eb"
    );
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 9, 0);

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private LoadSettlementPort loadSettlementPort;

    @Mock
    private LoadSettlementExpensePort loadSettlementExpensePort;

    @Mock
    private SaveSettlementExpensePort saveSettlementExpensePort;

    @Mock
    private DeleteSettlementExpensePort deleteSettlementExpensePort;

    @Mock
    private SaveSettlementPort saveSettlementPort;

    @Mock
    private SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    private SettlementExpenseService settlementExpenseService;

    @BeforeEach
    void setUp() {
        settlementExpenseService = new SettlementExpenseService(
                currentActorPort,
                loadSettlementPort,
                loadSettlementExpensePort,
                saveSettlementExpensePort,
                deleteSettlementExpensePort,
                saveSettlementPort,
                allocationDraftInvalidator
        );
        lenient().when(allocationDraftInvalidator.invalidate(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("동시 등록과 상태 확정을 직렬화하도록 정산을 잠근 뒤 다음 순서로 수동 지출을 저장한다")
    void manualExpense_concurrentDraftWrites_savedUnderLock() {
        givenOwnedDraftForUpdate();
        when(loadSettlementExpensePort.nextDisplayOrder(SETTLEMENT_ID)).thenReturn(2);
        when(saveSettlementExpensePort.save(any(SettlementExpense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID)).thenReturn(10_000L);

        var result = settlementExpenseService.create(
                SETTLEMENT_ID,
                new CreateSettlementExpenseCommand("저녁 식사", 42_000)
        );

        ArgumentCaptor<SettlementExpense> captor = ArgumentCaptor.forClass(SettlementExpense.class);
        verify(loadSettlementPort).findByIdForUpdate(SETTLEMENT_ID);
        verify(allocationDraftInvalidator).invalidate(any(Settlement.class), any(LocalDateTime.class));
        verify(saveSettlementExpensePort).save(captor.capture());
        assertThat(captor.getValue().displayOrder()).isEqualTo(2);
        assertThat(result.expense().description()).isEqualTo("저녁 식사");
        assertThat(result.totalExpenseAmount()).isEqualTo(52_000);
    }

    @Test
    @DisplayName("등록 후 누적액이 long 범위를 넘으면 저장 전에 명시적인 입력 오류를 반환한다")
    void manualExpense_totalOverflow_rejectedBeforeSave() {
        givenOwnedDraftForUpdate();
        when(loadSettlementExpensePort.nextDisplayOrder(SETTLEMENT_ID)).thenReturn(1);
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID))
                .thenReturn(Long.MAX_VALUE);

        assertThatThrownBy(() -> settlementExpenseService.create(
                SETTLEMENT_ID,
                new CreateSettlementExpenseCommand("추가 지출", 1)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL
                ));
        verify(saveSettlementExpensePort, never()).save(any(SettlementExpense.class));
    }

    @Test
    @DisplayName("목록과 누적액이 같은 조회 결과를 반영하도록 지출 목록에서 합계를 계산한다")
    void settlementExpenseList_listAndTotal_consistent() {
        givenOwnedDraft();
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                expense(EXPENSE_ID, "저녁 식사", 42_000, 0),
                expense(UUID.randomUUID(), "카페", 8_000, 1)
        ));

        var result = settlementExpenseService.get(SETTLEMENT_ID);

        assertThat(result.expenses()).extracting(item -> item.description())
                .containsExactly("저녁 식사", "카페");
        assertThat(result.totalExpenseAmount()).isEqualTo(50_000);
        verify(loadSettlementExpensePort, never()).totalAmount(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("외부에서 유입된 지출 목록의 합계가 long 범위를 넘으면 500 대신 명시적인 오류를 반환한다")
    void settlementExpenseTotal_externalOverflow_returnsExplicitError() {
        givenOwnedDraft();
        when(loadSettlementExpensePort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(List.of(
                expense(EXPENSE_ID, "최대 금액", Long.MAX_VALUE, 0),
                expense(UUID.randomUUID(), "추가 금액", 1, 1)
        ));

        assertThatThrownBy(() -> settlementExpenseService.get(SETTLEMENT_ID))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL
                        ));
    }

    @Test
    @DisplayName("생성자만 지출을 바꿀 수 있도록 다른 사용자의 등록을 저장하지 않는다")
    void manualExpense_otherUser_notSaved() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(OTHER_USER_ID));

        assertThatThrownBy(() -> settlementExpenseService.create(
                SETTLEMENT_ID,
                new CreateSettlementExpenseCommand("저녁 식사", 42_000)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_ACCESS_DENIED
                ));
        verify(saveSettlementExpensePort, never()).save(any(SettlementExpense.class));
    }

    @Test
    @DisplayName("요청 금액이 바뀌지 않도록 DRAFT가 아닌 정산의 지출 등록을 거부한다")
    void rejectsCreationOutsideDraft() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(SettlementStatus.REQUESTED)));
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));

        assertThatThrownBy(() -> settlementExpenseService.create(
                SETTLEMENT_ID,
                new CreateSettlementExpenseCommand("저녁 식사", 42_000)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_NOT_EDITABLE
                ));
        verify(saveSettlementExpensePort, never()).save(any(SettlementExpense.class));
    }

    @Test
    @DisplayName("부분 수정 결과와 최신 누적액을 반환한다")
    void updatesManualExpense() {
        givenOwnedDraftForUpdate();
        when(loadSettlementExpensePort.findBySettlementIdAndId(SETTLEMENT_ID, EXPENSE_ID))
                .thenReturn(Optional.of(expense(EXPENSE_ID, "저녁 식사", 42_000, 0)));
        when(saveSettlementExpensePort.update(any(SettlementExpense.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID)).thenReturn(50_000L);

        var result = settlementExpenseService.update(
                SETTLEMENT_ID,
                EXPENSE_ID,
                new UpdateSettlementExpenseCommand(null, 50_000L)
        );

        assertThat(result.expense().description()).isEqualTo("저녁 식사");
        assertThat(result.expense().amount()).isEqualTo(50_000);
        assertThat(result.totalExpenseAmount()).isEqualTo(58_000);
    }

    @Test
    @DisplayName("수정 후 누적액이 long 범위를 넘으면 기존 지출을 변경하지 않는다")
    void rejectsUpdateThatOverflowsTotal() {
        givenOwnedDraftForUpdate();
        when(loadSettlementExpensePort.findBySettlementIdAndId(SETTLEMENT_ID, EXPENSE_ID))
                .thenReturn(Optional.of(expense(EXPENSE_ID, "기존 지출", 1, 0)));
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID))
                .thenReturn(Long.MAX_VALUE);

        assertThatThrownBy(() -> settlementExpenseService.update(
                SETTLEMENT_ID,
                EXPENSE_ID,
                new UpdateSettlementExpenseCommand(null, 2L)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL
                ));
        verify(saveSettlementExpensePort, never()).update(any(SettlementExpense.class));
    }

    @Test
    @DisplayName("다른 정산의 지출 ID로 수정할 수 없도록 소속이 일치하지 않으면 찾을 수 없음으로 처리한다")
    void rejectsExpenseOutsideSettlement() {
        givenOwnedDraftForUpdate();
        when(loadSettlementExpensePort.findBySettlementIdAndId(SETTLEMENT_ID, EXPENSE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementExpenseService.update(
                SETTLEMENT_ID,
                EXPENSE_ID,
                new UpdateSettlementExpenseCommand("변경", null)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_EXPENSE_NOT_FOUND
                ));
        verify(saveSettlementExpensePort, never()).update(any(SettlementExpense.class));
    }

    @Test
    @DisplayName("삭제 뒤 남은 지출 누적액을 반환한다")
    void removesManualExpense() {
        givenOwnedDraftForUpdate();
        SettlementExpense expense = expense(EXPENSE_ID, "저녁 식사", 42_000, 0);
        when(loadSettlementExpensePort.findBySettlementIdAndId(SETTLEMENT_ID, EXPENSE_ID))
                .thenReturn(Optional.of(expense));
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID)).thenReturn(50_000L);

        var result = settlementExpenseService.remove(SETTLEMENT_ID, EXPENSE_ID);

        verify(deleteSettlementExpensePort).delete(expense);
        assertThat(result.totalExpenseAmount()).isEqualTo(8_000);
    }

    @Test
    @DisplayName("잘못 가져온 결제내역 지출도 초안에서 삭제할 수 있다")
    void removesImportedExpense() {
        givenOwnedDraftForUpdate();
        SettlementExpense expense = SettlementExpense.paymentHistory(
                SETTLEMENT_ID,
                1L,
                "transaction-key",
                "카드 결제",
                42_000,
                null,
                0,
                CREATED_AT
        );
        when(loadSettlementExpensePort.findBySettlementIdAndId(SETTLEMENT_ID, expense.id()))
                .thenReturn(Optional.of(expense));
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID)).thenReturn(50_000L);

        var result = settlementExpenseService.remove(SETTLEMENT_ID, expense.id());

        verify(deleteSettlementExpensePort).delete(expense);
        assertThat(result.totalExpenseAmount()).isEqualTo(8_000);
    }

    private void givenOwnedDraftForUpdate() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
    }

    private void givenOwnedDraft() {
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
    }

    private Settlement draft() {
        return settlement(SettlementStatus.DRAFT);
    }

    private Settlement settlement(SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                CREATOR_ID,
                "회식 정산",
                50_000,
                status,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementExpense expense(
            UUID expenseId,
            String description,
            long amount,
            int displayOrder
    ) {
        return new SettlementExpense(
                expenseId,
                SETTLEMENT_ID,
                com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource.MANUAL,
                description,
                amount,
                displayOrder,
                CREATED_AT,
                CREATED_AT
        );
    }
}
