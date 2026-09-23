package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementPaymentExpenseCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ImportSettlementPaymentExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ImportSettlementPaymentExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementPaymentExpenseCandidateResult;
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
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("결제내역 정산 지출 Web API 계약")
class SettlementPaymentExpenseControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final UUID EXPENSE_ID = UUID.fromString(
            "798cb149-f804-4427-9194-73ebdd7ff2eb"
    );
    private static final LocalDateTime TRANSACTED_AT = LocalDateTime.of(2026, 8, 23, 9, 0);

    @Mock private GetSettlementPaymentExpenseCandidatesUseCase getCandidatesUseCase;
    @Mock private ImportSettlementPaymentExpenseUseCase importExpenseUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        SettlementPaymentExpenseController controller = new SettlementPaymentExpenseController(
                getCandidatesUseCase,
                importExpenseUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("월별 출금 후보와 기존 가져오기 여부를 반환한다")
    void paymentExpenseCandidates_monthly_returnsImportState() throws Exception {
        when(getCandidatesUseCase.get(
                SETTLEMENT_ID,
                10L,
                YearMonth.of(2026, 8)
        )).thenReturn(List.of(new SettlementPaymentExpenseCandidateResult(
                20L,
                "점심",
                12_000,
                TRANSACTED_AT,
                true
        )));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/expense-candidates/payment-history",
                        SETTLEMENT_ID
                ).param("accountId", "10").param("yearMonth", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].transactionId").value(20))
                .andExpect(jsonPath("$.data[0].description").value("점심"))
                .andExpect(jsonPath("$.data[0].amount").value(12_000))
                .andExpect(jsonPath("$.data[0].imported").value(true));
    }

    @Test
    @DisplayName("선택한 결제내역을 정산 지출로 가져온 결과를 반환한다")
    void paymentExpense_selectedPayment_mapsToSettlementExpense() throws Exception {
        when(importExpenseUseCase.importExpense(
                SETTLEMENT_ID,
                new ImportSettlementPaymentExpenseCommand(10L, 20L)
        )).thenReturn(new SettlementExpenseMutationResult(
                new SettlementExpenseResult(
                        EXPENSE_ID,
                        SETTLEMENT_ID,
                        SettlementExpenseSource.PAYMENT_HISTORY,
                        "점심",
                        12_000,
                        0,
                        TRANSACTED_AT,
                        TRANSACTED_AT
                ),
                20_000
        ));

        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/expenses/payment-history",
                        SETTLEMENT_ID
                ).contentType("application/json").content("""
                        {
                          "accountId": 10,
                          "transactionId": 20
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.source").value("PAYMENT_HISTORY"))
                .andExpect(jsonPath("$.data.expense.description").value("점심"))
                .andExpect(jsonPath("$.data.expense.amount").value(12_000))
                .andExpect(jsonPath("$.data.totalExpenseAmount").value(20_000));
    }

    @Test
    @DisplayName("양수가 아닌 계좌 ID와 결제내역 ID를 UseCase 호출 전에 거부한다")
    void paymentExpenseImport_invalidIds_rejectedBeforeUseCase() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/expenses/payment-history",
                        SETTLEMENT_ID
                ).contentType("application/json").content("""
                        {
                          "accountId": 0,
                          "transactionId": -1
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(importExpenseUseCase);
    }
}
