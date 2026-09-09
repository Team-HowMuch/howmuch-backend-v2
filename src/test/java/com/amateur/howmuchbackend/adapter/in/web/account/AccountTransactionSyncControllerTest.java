package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.application.port.in.account.GetAccountTransactionSyncJobUseCase;
import com.amateur.howmuchbackend.application.port.in.account.RequestAccountTransactionSyncUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.RequestAccountTransactionSyncCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionSyncJobResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.account.AccountTransactionSyncStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 거래 동기화 Web API 계약")
class AccountTransactionSyncControllerTest {
    private static final UUID IDEMPOTENCY_KEY = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SYNC_JOB_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Mock private RequestAccountTransactionSyncUseCase requestUseCase;
    @Mock private GetAccountTransactionSyncJobUseCase getUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AccountTransactionSyncController(requestUseCase, getUseCase)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("UUID 멱등성 Key와 날짜 범위를 202 작업으로 접수한다")
    void acceptsSyncJob() throws Exception {
        RequestAccountTransactionSyncCommand command = new RequestAccountTransactionSyncCommand(
                10L, IDEMPOTENCY_KEY, LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31")
        );
        when(requestUseCase.request(command)).thenReturn(result());

        mockMvc.perform(post("/api/v2/accounts/10/transaction-sync-jobs")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromDate":"2026-08-01","toDate":"2026-08-31"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.syncJobId").value(SYNC_JOB_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        verify(requestUseCase).request(command);
    }

    @Test
    @DisplayName("멱등성 Key 누락과 잘못된 UUID를 INVALID_ARGUMENT로 거절한다")
    void rejectsMissingOrMalformedIdempotencyKey() throws Exception {
        String body = "{\"fromDate\":\"2026-08-01\",\"toDate\":\"2026-08-31\"}";
        mockMvc.perform(post("/api/v2/accounts/10/transaction-sync-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v2/accounts/10/transaction-sync-jobs")
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("소유한 동기화 작업 상태를 조회한다")
    void getsSyncJob() throws Exception {
        when(getUseCase.get(SYNC_JOB_ID)).thenReturn(result());

        mockMvc.perform(get("/api/v2/account-transaction-sync-jobs/{syncJobId}", SYNC_JOB_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(10))
                .andExpect(jsonPath("$.data.fromDate").value("2026-08-01"));
    }

    private AccountTransactionSyncJobResult result() {
        return new AccountTransactionSyncJobResult(
                SYNC_JOB_ID,
                10L,
                AccountTransactionSyncStatus.QUEUED,
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-08-31"),
                LocalDateTime.parse("2026-09-09T03:00:00"),
                null,
                null,
                null,
                null
        );
    }
}
