package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.ExtractReceiptItemsPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OCR 영수증 정산 지출 Application 흐름")
class SettlementReceiptExpenseServiceTest {
    private static final Long CREATOR_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 20, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 8, 31, 17, 40);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementExpensePort loadSettlementExpensePort;
    @Mock private LoadSettlementItemPort loadSettlementItemPort;
    @Mock private ExtractReceiptItemsPort extractReceiptItemsPort;
    @Mock private SettlementReceiptExpenseWriter receiptExpenseWriter;

    private SettlementReceiptExpenseService service;

    @BeforeEach
    void setUp() {
        service = new SettlementReceiptExpenseService(
                currentActorPort,
                loadSettlementPort,
                loadSettlementExpensePort,
                loadSettlementItemPort,
                extractReceiptItemsPort,
                receiptExpenseWriter
        );
    }

    @Test
    @DisplayName("앱 영수증 품목의 행 금액 합계를 서버에서 계산해 Writer에 전달한다")
    void receiptExpense_appItems_calculatesTotalOnServer() {
        CreateSettlementReceiptExpenseCommand command =
                new CreateSettlementReceiptExpenseCommand(
                        "  receipt-app-1  ",
                        "  롯데리아  ",
                        PAID_AT,
                        List.of(
                                new CreateSettlementReceiptExpenseCommand.Item(
                                        "  불고기버거  ",
                                        2,
                                        10_000
                                ),
                                new CreateSettlementReceiptExpenseCommand.Item(
                                        "감자튀김",
                                        1,
                                        3_000
                                )
                        )
                );
        ExtractReceiptItemsPort.ReceiptExtraction extraction =
                new ExtractReceiptItemsPort.ReceiptExtraction(
                        "롯데리아",
                        13_000,
                        List.of(
                                new ExtractReceiptItemsPort.ReceiptItem("불고기버거", 2, 10_000),
                                new ExtractReceiptItemsPort.ReceiptItem("감자튀김", 1, 3_000)
                        )
                );
        SettlementReceiptExpenseMutationResult expected =
                new SettlementReceiptExpenseMutationResult(null, List.of(), 13_000);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(receiptExpenseWriter.write(
                SETTLEMENT_ID,
                CREATOR_ID,
                "receipt-app-1",
                PAID_AT,
                extraction
        )).thenReturn(expected);

        assertThat(service.create(SETTLEMENT_ID, command)).isSameAs(expected);
        verifyNoInteractions(extractReceiptItemsPort, loadSettlementPort);
    }

    @Test
    @DisplayName("앱 영수증 품목 합계 overflow를 잘못된 요청으로 거부한다")
    void receiptExpense_totalOverflow_rejected() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        CreateSettlementReceiptExpenseCommand command =
                new CreateSettlementReceiptExpenseCommand(
                        "receipt-app-1",
                        "롯데리아",
                        null,
                        List.of(
                                new CreateSettlementReceiptExpenseCommand.Item(
                                        "품목1",
                                        1,
                                        Long.MAX_VALUE
                                ),
                                new CreateSettlementReceiptExpenseCommand.Item("품목2", 1, 1)
                        )
                );

        assertThatThrownBy(() -> service.create(SETTLEMENT_ID, command))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(
                                SettlementStatusCode.INVALID_SETTLEMENT_RECEIPT_IMPORT_REQUEST
                        ));
        verifyNoInteractions(receiptExpenseWriter);
    }

    @Test
    @DisplayName("OCR 외부 호출은 쓰기 트랜잭션 밖에서 실행하고 저장만 별도 트랜잭션을 사용한다")
    void receiptExpense_ocr_runsOutsideWriteTransaction() throws NoSuchMethodException {
        var importMethod = SettlementReceiptExpenseService.class.getMethod(
                "importExpense",
                UUID.class,
                ImportSettlementReceiptExpenseCommand.class
        );
        var writeMethod = SettlementReceiptExpenseWriter.class.getMethod(
                "write",
                UUID.class,
                Long.class,
                String.class,
                ExtractReceiptItemsPort.ReceiptExtraction.class
        );
        var transactionAttributes = new AnnotationTransactionAttributeSource();

        assertThat(transactionAttributes.getTransactionAttribute(
                importMethod,
                SettlementReceiptExpenseService.class
        )).isNull();
        assertThat(transactionAttributes.getTransactionAttribute(
                writeMethod,
                SettlementReceiptExpenseWriter.class
        )).isNotNull();
    }

    @Test
    @DisplayName("생성자와 DRAFT를 먼저 검증한 뒤 OCR 결과를 저장 컴포넌트에 전달한다")
    void receiptExpense_validatedDraft_delegatesExtractedReceipt() {
        Settlement settlement = settlement(CREATOR_ID, SettlementStatus.DRAFT);
        ExtractReceiptItemsPort.ReceiptExtraction extraction = extraction(30_000);
        SettlementReceiptExpenseMutationResult expected =
                new SettlementReceiptExpenseMutationResult(null, List.of(), 30_000);
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));
        when(extractReceiptItemsPort.extract(CREATOR_ID, "receipt-demo"))
                .thenReturn(Optional.of(extraction));
        when(receiptExpenseWriter.write(
                SETTLEMENT_ID,
                CREATOR_ID,
                "receipt-demo",
                extraction
        )).thenReturn(expected);

        var result = service.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementReceiptExpenseCommand("  receipt-demo  ")
        );

        assertThat(result).isSameAs(expected);
        verify(extractReceiptItemsPort).extract(CREATOR_ID, "receipt-demo");
    }

    @Test
    @DisplayName("다른 사용자는 비용이 발생하는 OCR 호출 전에 거부한다")
    void receiptExpense_otherUser_rejectedBeforeOcr() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(2L));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(CREATOR_ID, SettlementStatus.DRAFT)));

        assertThatThrownBy(() -> service.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementReceiptExpenseCommand("receipt-demo")
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_ACCESS_DENIED
                ));
        verify(extractReceiptItemsPort, never()).extract(CREATOR_ID, "receipt-demo");
    }

    @Test
    @DisplayName("OCR 결과가 없으면 지출 저장을 시작하지 않는다")
    void receiptExpense_missingOcrResult_rejectedBeforeWrite() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(CREATOR_ID, SettlementStatus.DRAFT)));
        when(extractReceiptItemsPort.extract(CREATOR_ID, "missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementReceiptExpenseCommand("missing")
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_RECEIPT_EXTRACTION_NOT_FOUND
                ));
        verify(receiptExpenseWriter, never()).write(
                SETTLEMENT_ID,
                CREATOR_ID,
                "missing",
                extraction(30_000)
        );
    }

    @Test
    @DisplayName("이미 가져온 OCR 참조는 외부 OCR을 다시 호출하지 않는다")
    void rejectsDuplicateBeforeExternalExtraction() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(CREATOR_ID));
        when(loadSettlementPort.findById(SETTLEMENT_ID))
                .thenReturn(Optional.of(settlement(CREATOR_ID, SettlementStatus.DRAFT)));
        when(loadSettlementExpensePort.existsBySettlementIdAndSourceAndSourceReference(
                SETTLEMENT_ID,
                SettlementExpenseSource.RECEIPT_OCR,
                "receipt-demo"
        )).thenReturn(true);

        assertThatThrownBy(() -> service.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementReceiptExpenseCommand("receipt-demo")
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_RECEIPT_ALREADY_IMPORTED
                ));
        verify(extractReceiptItemsPort, never())
                .extract(CREATOR_ID, "receipt-demo");
    }

    private Settlement settlement(Long creatorId, SettlementStatus status) {
        return new Settlement(
                SETTLEMENT_ID,
                creatorId,
                "영수증 정산",
                30_000,
                status,
                CREATED_AT,
                CREATED_AT
        );
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
