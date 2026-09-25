package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.settlement.ExtractReceiptItemsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OCR 영수증 정산 지출 원자 저장")
class SettlementReceiptExpenseWriterTest {
    private static final Long CREATOR_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 20, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 8, 31, 17, 40);

    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private SaveSettlementExpensePort saveSettlementExpensePort;
    @Mock private SaveSettlementItemPort saveSettlementItemPort;
    @Mock private SettlementAllocationDraftInvalidator allocationDraftInvalidator;

    private SettlementReceiptExpenseWriter writer;

    @BeforeEach
    void setUp() {
        writer = new SettlementReceiptExpenseWriter(
                loadSettlementPort,
                saveSettlementPort,
                loadSettlementExpensePort,
                saveSettlementExpensePort,
                saveSettlementItemPort,
                allocationDraftInvalidator
        );
        lenient().when(allocationDraftInvalidator.invalidate(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("잠긴 DRAFT 정산에 OCR 지출과 순서가 보존된 품목을 함께 저장한다")
    void receiptExpense_draft_savesReceiptAndOrderedItems() {
        givenOwnedDraft();
        when(loadSettlementExpensePort.nextDisplayOrder(SETTLEMENT_ID)).thenReturn(2);
        when(loadSettlementExpensePort.totalAmount(SETTLEMENT_ID)).thenReturn(5_000L);
        when(saveSettlementExpensePort.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(saveSettlementItemPort.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = writer.write(
                SETTLEMENT_ID,
                CREATOR_ID,
                "receipt-demo",
                PAID_AT,
                extraction(30_000)
        );

        ArgumentCaptor<SettlementExpense> expenseCaptor =
                ArgumentCaptor.forClass(SettlementExpense.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SettlementItem>> itemCaptor = ArgumentCaptor.forClass(List.class);
        verify(saveSettlementExpensePort).save(expenseCaptor.capture());
        verify(saveSettlementItemPort).saveAll(itemCaptor.capture());
        assertThat(expenseCaptor.getValue().source())
                .isEqualTo(SettlementExpenseSource.RECEIPT_OCR);
        assertThat(expenseCaptor.getValue().sourceReference()).isEqualTo("receipt-demo");
        assertThat(expenseCaptor.getValue().paidAt()).isEqualTo(PAID_AT);
        assertThat(expenseCaptor.getValue().displayOrder()).isEqualTo(2);
        assertThat(itemCaptor.getValue()).extracting(SettlementItem::displayOrder)
                .containsExactly(0, 1);
        assertThat(itemCaptor.getValue()).allSatisfy(item ->
                assertThat(item.expenseId()).isEqualTo(expenseCaptor.getValue().id()));
        assertThat(result.totalExpenseAmount()).isEqualTo(35_000);
        assertThat(result.items()).hasSize(2);
    }

    @Test
    @DisplayName("품목 옵션과 할인은 결제액을 검증한 뒤 원자적으로 저장한다")
    void receiptExpense_optionsAndDiscounts_savedWithNetTotal() {
        givenOwnedDraft();
        when(saveSettlementExpensePort.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(saveSettlementItemPort.saveAll(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ExtractReceiptItemsPort.ReceiptExtraction extraction =
                new ExtractReceiptItemsPort.ReceiptExtraction(
                        "커피점", 900,
                        List.of(new ExtractReceiptItemsPort.ReceiptItem(
                                "커피", 1, 1_100, 100,
                                List.of(new ExtractReceiptItemsPort.ReceiptOption("샷 추가", 100))
                        )),
                        100
                );

        var result = writer.write(SETTLEMENT_ID, CREATOR_ID, "receipt-discount", extraction);

        assertThat(result.expense().amount()).isEqualTo(900);
        assertThat(result.expense().discount()).isEqualTo(100);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.amount()).isEqualTo(1_100);
            assertThat(item.discount()).isEqualTo(100);
            assertThat(item.options()).singleElement()
                    .satisfies(option -> assertThat(option.name()).isEqualTo("샷 추가"));
        });
    }

    @Test
    @DisplayName("OCR 총액과 품목 합계가 다르면 어떤 지출도 저장하지 않는다")
    void receiptExpense_totalMismatch_rejectedBeforePersistence() {
        givenOwnedDraft();

        assertThatThrownBy(() -> writer.write(
                SETTLEMENT_ID,
                CREATOR_ID,
                "receipt-demo",
                extraction(29_000)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_RECEIPT_EXTRACTION_INVALID
                ));
        verify(saveSettlementExpensePort, never()).save(any());
        verify(saveSettlementItemPort, never()).saveAll(any());
    }

    @Test
    @DisplayName("같은 OCR 결과 참조는 정산 행 잠금 뒤 중복 저장을 거부한다")
    void receiptExpense_duplicateReference_rejectedAfterLock() {
        givenOwnedDraft();
        when(loadSettlementExpensePort.existsBySettlementIdAndSourceAndSourceReference(
                SETTLEMENT_ID,
                SettlementExpenseSource.RECEIPT_OCR,
                "receipt-demo"
        )).thenReturn(true);

        assertThatThrownBy(() -> writer.write(
                SETTLEMENT_ID,
                CREATOR_ID,
                "receipt-demo",
                extraction(30_000)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_RECEIPT_ALREADY_IMPORTED
                ));
        verify(saveSettlementExpensePort, never()).save(any());
    }

    private void givenOwnedDraft() {
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID))
                .thenReturn(Optional.of(new Settlement(
                        SETTLEMENT_ID,
                        CREATOR_ID,
                        "영수증 정산",
                        30_000,
                        SettlementStatus.DRAFT,
                        CREATED_AT,
                        CREATED_AT
                )));
    }

    private ExtractReceiptItemsPort.ReceiptExtraction extraction(long totalAmount) {
        return new ExtractReceiptItemsPort.ReceiptExtraction(
                "OCR 영수증",
                totalAmount,
                List.of(
                        new ExtractReceiptItemsPort.ReceiptItem("커피", 2, 10_000),
                        new ExtractReceiptItemsPort.ReceiptItem("샌드위치", 2, 20_000)
                )
        );
    }
}
