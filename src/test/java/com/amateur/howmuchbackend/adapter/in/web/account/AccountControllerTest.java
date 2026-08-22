package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.application.port.in.account.ConnectBankUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountDetailUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;
import com.amateur.howmuchbackend.application.port.in.account.LinkBankAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountDetailResult;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountSummaryResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.account.AccountSort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 Web API 계약")
class AccountControllerTest {
    @Mock private GetBankCatalogUseCase getBankCatalogUseCase;
    @Mock private ConnectBankUseCase connectBankUseCase;
    @Mock private LinkBankAccountsUseCase linkBankAccountsUseCase;
    @Mock private GetAccountsUseCase getAccountsUseCase;
    @Mock private GetAccountDetailUseCase getAccountDetailUseCase;
    @Mock private GetAccountTransactionsUseCase getAccountTransactionsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AccountController controller = new AccountController(
                getBankCatalogUseCase,
                connectBankUseCase,
                linkBankAccountsUseCase,
                getAccountsUseCase,
                getAccountDetailUseCase,
                getAccountTransactionsUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("기관 필터와 최근 정렬 결과를 공개 계좌번호 없이 반환한다")
    void getsAccounts() throws Exception {
        var query = new GetAccountsQuery("0020", AccountSort.RECENT);
        when(getAccountsUseCase.getAccounts(query)).thenReturn(List.of(new AccountSummaryResult(
                1L, "0020", "우리은행", "woori.png", "생활비 통장", "****6789",
                true, 0, LocalDateTime.of(2026, 8, 20, 0, 15)
        )));

        mockMvc.perform(get("/api/v2/accounts")
                        .param("organization", "0020")
                        .param("sort", "RECENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].institutionName").value("우리은행"))
                .andExpect(jsonPath("$.data[0].maskedAccountNumber").value("****6789"))
                .andExpect(jsonPath("$.data[0].externalAccountKey").doesNotExist());
    }

    @Test
    @DisplayName("요청 월 소비 요약을 YYYY-MM 계약으로 반환한다")
    void getsAccountDetail() throws Exception {
        YearMonth august = YearMonth.of(2026, 8);
        when(getAccountDetailUseCase.getAccountDetail(1L, august)).thenReturn(new AccountDetailResult(
                1L, "0020", "우리은행", "woori.png", "생활비 통장", "****6789",
                true, 0, august, new BigDecimal("12500.0000"), 1,
                LocalDateTime.of(2026, 8, 20, 0, 15)
        ));

        mockMvc.perform(get("/api/v2/accounts/1").param("yearMonth", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.yearMonth").value("2026-08"))
                .andExpect(jsonPath("$.data.monthlyExpenseAmount").value(12500.0000))
                .andExpect(jsonPath("$.data.expenseCount").value(1));
    }

    @Test
    @DisplayName("잘못된 요청 월 형식은 INVALID_ARGUMENT로 거절한다")
    void rejectsInvalidYearMonth() throws Exception {
        mockMvc.perform(get("/api/v2/accounts/1").param("yearMonth", "2026-8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }
}
