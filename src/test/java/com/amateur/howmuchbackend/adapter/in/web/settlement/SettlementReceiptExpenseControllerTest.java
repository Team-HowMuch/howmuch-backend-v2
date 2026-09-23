package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementReceiptExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementReceiptExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemListResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceiptExpenseMutationResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("OCR 영수증 정산 지출 Web API 계약")
class SettlementReceiptExpenseControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final UUID EXPENSE_ID = UUID.fromString(
            "798cb149-f804-4427-9194-73ebdd7ff2eb"
    );
    private static final UUID ITEM_ID = UUID.fromString(
            "3f0b00ad-c146-4fc0-9539-f08a84027247"
    );
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 20, 0);
    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 8, 31, 17, 40);

    @Mock private CreateSettlementReceiptExpenseUseCase createExpenseUseCase;
    @Mock private ImportSettlementReceiptExpenseUseCase importExpenseUseCase;
    @Mock private GetSettlementItemsUseCase getSettlementItemsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        SettlementReceiptExpenseController controller =
                new SettlementReceiptExpenseController(
                        createExpenseUseCase,
                        importExpenseUseCase,
                        getSettlementItemsUseCase
                );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("앱이 읽은 영수증 품목과 결제 정보를 등록한다")
    void receiptExpense_appResult_mapsItemsAndPayment() throws Exception {
        CreateSettlementReceiptExpenseCommand command =
                new CreateSettlementReceiptExpenseCommand(
                        "receipt-app-1",
                        "롯데리아",
                        PAID_AT,
                        List.of(new CreateSettlementReceiptExpenseCommand.Item(
                                "불고기버거",
                                2,
                                10_000
                        ))
                );
        when(createExpenseUseCase.create(SETTLEMENT_ID, command)).thenReturn(
                new SettlementReceiptExpenseMutationResult(
                        expenseResult(),
                        List.of(itemResult()),
                        10_000
                )
        );

        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/expenses/receipt",
                        SETTLEMENT_ID
                ).contentType("application/json").content("""
                        {
                          "receiptReference": "receipt-app-1",
                          "storeName": "롯데리아",
                          "paidAt": "2026-08-31T17:40:00",
                          "items": [
                            {"name": "불고기버거", "quantity": 2, "amount": 10000}
                          ]
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.source").value("RECEIPT_OCR"))
                .andExpect(jsonPath("$.data.totalExpenseAmount").value(10_000));
    }

    @Test
    @DisplayName("앱 영수증의 빈 품목 목록은 UseCase 호출 전에 거부한다")
    void receiptExpense_emptyItems_rejectedBeforeUseCase() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/expenses/receipt",
                        SETTLEMENT_ID
                ).contentType("application/json").content("""
                        {
                          "receiptReference": "receipt-app-1",
                          "storeName": "롯데리아",
                          "items": []
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(createExpenseUseCase);
    }

    @Test
    @DisplayName("OCR 영수증을 지출과 품목으로 가져온 결과를 반환한다")
    void receiptExpense_ocrImport_returnsExpenseAndItems() throws Exception {
        when(importExpenseUseCase.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementReceiptExpenseCommand("receipt-demo")
        )).thenReturn(new SettlementReceiptExpenseMutationResult(
                expenseResult(),
                List.of(itemResult()),
                35_000
        ));

        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/expenses/receipt-ocr",
                        SETTLEMENT_ID
                ).contentType("application/json").content("""
                        {
                          "receiptReference": "receipt-demo"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.source").value("RECEIPT_OCR"))
                .andExpect(jsonPath("$.data.expense.amount").value(30_000))
                .andExpect(jsonPath("$.data.items[0].itemId").value(ITEM_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].name").value("아메리카노"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].amount").value(10_000))
                .andExpect(jsonPath("$.data.totalExpenseAmount").value(35_000));
    }

    @Test
    @DisplayName("정산에 저장된 OCR 품목과 전체 합계를 반환한다")
    void settlementItems_request_returnsSavedItemsAndTotal() throws Exception {
        when(getSettlementItemsUseCase.get(SETTLEMENT_ID))
                .thenReturn(new SettlementItemListResult(List.of(itemResult()), 10_000));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/items",
                        SETTLEMENT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].expenseId")
                        .value(EXPENSE_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].displayOrder").value(0))
                .andExpect(jsonPath("$.data.totalItemAmount").value(10_000));
    }

    @Test
    @DisplayName("빈 OCR 영수증 참조는 UseCase 호출 전에 거부한다")
    void receiptExpense_blankReference_rejectedBeforeUseCase() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/expenses/receipt-ocr",
                        SETTLEMENT_ID
                ).contentType("application/json").content("""
                        {
                          "receiptReference": "  "
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(importExpenseUseCase);
    }

    private SettlementExpenseResult expenseResult() {
        return new SettlementExpenseResult(
                EXPENSE_ID,
                SETTLEMENT_ID,
                SettlementExpenseSource.RECEIPT_OCR,
                "OCR 영수증",
                30_000,
                0,
                CREATED_AT,
                CREATED_AT
        );
    }

    private SettlementItemResult itemResult() {
        return new SettlementItemResult(
                ITEM_ID,
                SETTLEMENT_ID,
                EXPENSE_ID,
                "아메리카노",
                2,
                10_000,
                0,
                CREATED_AT,
                CREATED_AT
        );
    }
}
