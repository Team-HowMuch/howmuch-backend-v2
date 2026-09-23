package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementExpensesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementExpenseUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementExpenseCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseListResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseMutationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementExpenseTotalResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 지출 Web API 계약")
class SettlementExpenseControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final UUID EXPENSE_ID = UUID.fromString(
            "798cb149-f804-4427-9194-73ebdd7ff2eb"
    );
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 9, 0);

    @Mock
    private CreateSettlementExpenseUseCase createSettlementExpenseUseCase;

    @Mock
    private GetSettlementExpensesUseCase getSettlementExpensesUseCase;

    @Mock
    private UpdateSettlementExpenseUseCase updateSettlementExpenseUseCase;

    @Mock
    private RemoveSettlementExpenseUseCase removeSettlementExpenseUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        SettlementExpenseController controller = new SettlementExpenseController(
                createSettlementExpenseUseCase,
                getSettlementExpensesUseCase,
                updateSettlementExpenseUseCase,
                removeSettlementExpenseUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("수동 지출을 등록하고 최신 누적액을 반환한다")
    void manualExpense_validRequest_returnsUpdatedTotal() throws Exception {
        when(createSettlementExpenseUseCase.create(
                SETTLEMENT_ID,
                new CreateSettlementExpenseCommand("저녁 식사", 42_000)
        )).thenReturn(mutationResult("저녁 식사", 42_000, 50_000));

        mockMvc.perform(post("/api/v2/settlements/{settlementId}/expenses", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "description": "  저녁 식사  ",
                                  "amount": 42000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.expense.expenseId").value(EXPENSE_ID.toString()))
                .andExpect(jsonPath("$.data.expense.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.expense.description").value("저녁 식사"))
                .andExpect(jsonPath("$.data.totalExpenseAmount").value(50_000));
    }

    @Test
    @DisplayName("유니코드 공백만 있는 설명을 UseCase 호출 전에 거부한다")
    void rejectsUnicodeWhitespaceDescription() throws Exception {
        mockMvc.perform(post("/api/v2/settlements/{settlementId}/expenses", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "description": "\u2003",
                                  "amount": 42000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(createSettlementExpenseUseCase);
    }

    @Test
    @DisplayName("지출 누적액이 long 범위를 넘으면 500이 아닌 400 오류 계약을 반환한다")
    void returnsBadRequestForExpenseTotalOverflow() throws Exception {
        when(createSettlementExpenseUseCase.create(
                SETTLEMENT_ID,
                new CreateSettlementExpenseCommand("추가 지출", 1)
        )).thenThrow(ApplicationException.of(
                SettlementStatusCode.INVALID_SETTLEMENT_EXPENSE_TOTAL
        ));

        mockMvc.perform(post("/api/v2/settlements/{settlementId}/expenses", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "description": "추가 지출",
                                  "amount": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_SETTLEMENT_EXPENSE_TOTAL"));
    }

    @Test
    @DisplayName("화면 복원을 위해 표시 순서 목록과 누적액을 반환한다")
    void getsExpenseList() throws Exception {
        when(getSettlementExpensesUseCase.get(SETTLEMENT_ID)).thenReturn(
                new SettlementExpenseListResult(
                        List.of(expenseResult("저녁 식사", 42_000)),
                        42_000
                )
        );

        mockMvc.perform(get("/api/v2/settlements/{settlementId}/expenses", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expenses[0].description").value("저녁 식사"))
                .andExpect(jsonPath("$.data.totalExpenseAmount").value(42_000));
    }

    @Test
    @DisplayName("수정 필드가 없는 PATCH를 거부한다")
    void rejectsPatchWithoutFields() throws Exception {
        mockMvc.perform(patch(
                        "/api/v2/settlements/{settlementId}/expenses/{expenseId}",
                        SETTLEMENT_ID,
                        EXPENSE_ID
                ).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(updateSettlementExpenseUseCase);
    }

    @Test
    @DisplayName("금액만 부분 수정해 수정된 지출과 최신 누적액을 반환한다")
    void updatesExpenseAmount() throws Exception {
        when(updateSettlementExpenseUseCase.update(
                eq(SETTLEMENT_ID),
                eq(EXPENSE_ID),
                eq(new UpdateSettlementExpenseCommand(null, 50_000L))
        )).thenReturn(mutationResult("저녁 식사", 50_000, 58_000));

        mockMvc.perform(patch(
                        "/api/v2/settlements/{settlementId}/expenses/{expenseId}",
                        SETTLEMENT_ID,
                        EXPENSE_ID
                ).contentType("application/json").content("""
                        {
                          "amount": 50000
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expense.amount").value(50_000))
                .andExpect(jsonPath("$.data.totalExpenseAmount").value(58_000));
    }

    @Test
    @DisplayName("삭제 뒤 남은 지출 누적액을 반환한다")
    void removesExpense() throws Exception {
        when(removeSettlementExpenseUseCase.remove(SETTLEMENT_ID, EXPENSE_ID))
                .thenReturn(new SettlementExpenseTotalResult(8_000));

        mockMvc.perform(delete(
                        "/api/v2/settlements/{settlementId}/expenses/{expenseId}",
                        SETTLEMENT_ID,
                        EXPENSE_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalExpenseAmount").value(8_000));
    }

    private SettlementExpenseMutationResult mutationResult(
            String description,
            long amount,
            long totalExpenseAmount
    ) {
        return new SettlementExpenseMutationResult(
                expenseResult(description, amount),
                totalExpenseAmount
        );
    }

    private SettlementExpenseResult expenseResult(String description, long amount) {
        return new SettlementExpenseResult(
                EXPENSE_ID,
                SETTLEMENT_ID,
                SettlementExpenseSource.MANUAL,
                description,
                amount,
                0,
                CREATED_AT,
                CREATED_AT
        );
    }
}
