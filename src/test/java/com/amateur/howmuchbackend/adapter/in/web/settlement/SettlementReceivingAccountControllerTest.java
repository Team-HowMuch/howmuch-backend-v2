package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementReceivingAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementReceivingAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementReceivingAccountCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceivingAccountResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 수령 계좌 Web API 계약")
class SettlementReceivingAccountControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("8b919c13-f797-42fa-b675-73f55dfc47eb");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 10, 0);

    @Mock private GetSettlementReceivingAccountsUseCase getReceivingAccountsUseCase;
    @Mock private SelectSettlementReceivingAccountUseCase selectReceivingAccountUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new SettlementReceivingAccountController(
                        getReceivingAccountsUseCase,
                        selectReceivingAccountUseCase
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("계좌 선택 화면을 구성하도록 후보 표시 정보와 선택 상태를 반환한다")
    void receivingAccountCandidates_request_returnsSelectionState() throws Exception {
        when(getReceivingAccountsUseCase.get(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementReceivingAccountResult(
                        7L,
                        "0020",
                        "우리은행",
                        "woori.png",
                        "생활비 통장",
                        "****6789",
                        true,
                        true
                )
        ));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/receiving-accounts",
                        SETTLEMENT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].accountId").value(7))
                .andExpect(jsonPath("$.data[0].institutionName").value("우리은행"))
                .andExpect(jsonPath("$.data[0].maskedAccountNumber").value("****6789"))
                .andExpect(jsonPath("$.data[0].isDefault").value(true))
                .andExpect(jsonPath("$.data[0].selected").value(true));
    }

    @Test
    @DisplayName("선택 결과를 정산 조회 계약으로 반환해 다음 단계가 현재 계좌를 알 수 있게 한다")
    void selectsReceivingAccount() throws Exception {
        when(selectReceivingAccountUseCase.select(
                eq(SETTLEMENT_ID),
                eq(new SelectSettlementReceivingAccountCommand(7L))
        )).thenReturn(new SettlementResult(
                SETTLEMENT_ID,
                1L,
                "회식 정산",
                10_000,
                null,
                7L,
                SettlementStatus.DRAFT,
                CREATED_AT
        ));

        mockMvc.perform(put(
                        "/api/v2/settlements/{settlementId}/receiving-account",
                        SETTLEMENT_ID
                )
                        .contentType("application/json")
                        .content("{\"receivingAccountId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.settlementId").value(SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.data.receivingAccountId").value(7));
    }

    @Test
    @DisplayName("DB 조회 전에 0 이하 수령 계좌 ID를 INVALID_ARGUMENT로 거부한다")
    void rejectsInvalidReceivingAccountId() throws Exception {
        mockMvc.perform(put(
                        "/api/v2/settlements/{settlementId}/receiving-account",
                        SETTLEMENT_ID
                )
                        .contentType("application/json")
                        .content("{\"receivingAccountId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        verifyNoInteractions(selectReceivingAccountUseCase);
    }
}
