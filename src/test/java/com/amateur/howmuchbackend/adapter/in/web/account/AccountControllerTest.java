package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.application.port.in.account.ConnectBankUseCase;
import com.amateur.howmuchbackend.application.port.in.account.DeleteAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountDetailUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;
import com.amateur.howmuchbackend.application.port.in.account.LinkBankAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.ReplaceAccountDisplayOrdersUseCase;
import com.amateur.howmuchbackend.application.port.in.account.SetDefaultAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.account.UpdateAccountDisplayOrderUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.ReplaceAccountDisplayOrdersCommand;
import com.amateur.howmuchbackend.application.port.in.account.command.UpdateAccountDisplayOrderCommand;
import com.amateur.howmuchbackend.application.port.in.account.command.GetAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountDetailResult;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountSummaryResult;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.account.AccountSort;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    @Mock private DeleteAccountUseCase deleteAccountUseCase;
    @Mock private SetDefaultAccountUseCase setDefaultAccountUseCase;
    @Mock private UpdateAccountDisplayOrderUseCase updateAccountDisplayOrderUseCase;
    @Mock private ReplaceAccountDisplayOrdersUseCase replaceAccountDisplayOrdersUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AccountController controller = new AccountController(
                getBankCatalogUseCase,
                connectBankUseCase,
                linkBankAccountsUseCase,
                getAccountsUseCase,
                getAccountDetailUseCase,
                getAccountTransactionsUseCase,
                deleteAccountUseCase,
                setDefaultAccountUseCase,
                updateAccountDisplayOrderUseCase,
                replaceAccountDisplayOrdersUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("기관 필터와 최근 정렬 결과를 공개 계좌번호 없이 반환한다")
    void accounts_byInstitutionAndRecentOrder_hidePublicNumber() throws Exception {
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
    void accountDetail_requestedMonth_returnsSpendSummary() throws Exception {
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
    @DisplayName("거래내역에 연결 정산 배열을 반환한다")
    void transactions_withSettlementLinks_includeArray() throws Exception {
        YearMonth august = YearMonth.of(2026, 8);
        UUID settlementId = UUID.fromString("13000000-0000-4000-8000-000000001001");
        when(getAccountTransactionsUseCase.getAccountTransactions(1L, august)).thenReturn(List.of(
                new AccountTransactionResult(
                        11L,
                        AccountTransactionType.EXPENSE,
                        new BigDecimal("12000"),
                        LocalDateTime.of(2026, 8, 20, 0, 15),
                        "식당",
                        new BigDecimal("100000"),
                        List.of(new AccountTransactionResult.SettlementSummary(
                                settlementId,
                                "점심 정산",
                                SettlementStatus.AWAITING_DEPOSIT
                        ))
                )
        ));

        mockMvc.perform(get("/api/v2/accounts/1/transactions")
                        .param("yearMonth", "2026-08"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].settlements[0].settlementId")
                        .value(settlementId.toString()))
                .andExpect(jsonPath("$.data[0].settlements[0].title").value("점심 정산"))
                .andExpect(jsonPath("$.data[0].settlements[0].status")
                        .value("AWAITING_DEPOSIT"))
                .andExpect(jsonPath("$.data[0].display.title").value("식당"))
                .andExpect(jsonPath("$.data[0].display.kind").value("OTHER"));
    }

    @Test
    @DisplayName("잘못된 요청 월 형식은 INVALID_ARGUMENT로 거절한다")
    void yearMonth_invalidFormat_returnsInvalidArgument() throws Exception {
        mockMvc.perform(get("/api/v2/accounts/1").param("yearMonth", "2026-8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("계좌를 삭제하고 빈 성공 응답을 반환한다")
    void account_delete_returnsEmptySuccess() throws Exception {
        mockMvc.perform(delete("/api/v2/accounts/1"))
                .andExpect(status().isOk());

        verify(deleteAccountUseCase).delete(1L);
    }

    @Test
    @DisplayName("기본 결제수단을 설정하고 빈 성공 응답을 반환한다")
    void defaultPaymentMethod_setAsDefault_returnsEmptySuccess() throws Exception {
        mockMvc.perform(patch("/api/v2/accounts/1/default"))
                .andExpect(status().isOk());

        verify(setDefaultAccountUseCase).setDefault(1L);
    }

    @Test
    @DisplayName("계좌 표시 순서를 변경하고 빈 성공 응답을 반환한다")
    void accountDisplayOrder_update_returnsEmptySuccess() throws Exception {
        mockMvc.perform(patch("/api/v2/accounts/1/display-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayOrder\":2}"))
                .andExpect(status().isOk());

        verify(updateAccountDisplayOrderUseCase).updateDisplayOrder(
                new UpdateAccountDisplayOrderCommand(1L, 2)
        );
    }

    @Test
    @DisplayName("음수 표시 순서는 INVALID_ACCOUNT_DISPLAY_ORDER로 거절한다")
    void accountDisplayOrder_negative_returnsInvalidOrder() throws Exception {
        doThrow(ApplicationException.of(AccountStatusCode.INVALID_ACCOUNT_DISPLAY_ORDER))
                .when(updateAccountDisplayOrderUseCase)
                .updateDisplayOrder(new UpdateAccountDisplayOrderCommand(1L, -1));

        mockMvc.perform(patch("/api/v2/accounts/1/display-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayOrder\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ACCOUNT_DISPLAY_ORDER"));
    }

    @Test
    @DisplayName("일괄 저장 경로가 단건 이동 경로와 겹치지 않고 순서 배열을 Command로 전달한다")
    void accountDisplayOrders_bulkSave_mapsToCommand() throws Exception {
        mockMvc.perform(patch("/api/v2/accounts/display-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountIds\":[11,12,10]}"))
                .andExpect(status().isOk());

        verify(replaceAccountDisplayOrdersUseCase).replaceDisplayOrders(
                new ReplaceAccountDisplayOrdersCommand(List.of(11L, 12L, 10L))
        );
    }

    @Test
    @DisplayName("활성 계좌가 빠진 순서는 INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST로 거절한다")
    void rejectsIncompleteDisplayOrderRequest() throws Exception {
        doThrow(ApplicationException.of(AccountStatusCode.INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST))
                .when(replaceAccountDisplayOrdersUseCase)
                .replaceDisplayOrders(new ReplaceAccountDisplayOrdersCommand(List.of(10L)));

        mockMvc.perform(patch("/api/v2/accounts/display-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountIds\":[10]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST"));
    }

    @Test
    @DisplayName("진행 중 정산이 사용하는 계좌 삭제는 409 계약으로 반환한다")
    void rejectsAccountUsedByInProgressSettlement() throws Exception {
        doThrow(ApplicationException.of(AccountStatusCode.ACCOUNT_IN_USE_BY_SETTLEMENT))
                .when(deleteAccountUseCase).delete(1L);

        mockMvc.perform(delete("/api/v2/accounts/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_IN_USE_BY_SETTLEMENT"));
    }
}
