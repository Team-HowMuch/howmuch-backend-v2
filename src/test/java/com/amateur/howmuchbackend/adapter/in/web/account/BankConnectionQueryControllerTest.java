package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.application.port.in.account.GetBankConnectionsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.GetBankConnectionAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.GetBankConnectionAccountsQuery;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionSummaryResult;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.external.ExternalConnectionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BankConnectionQueryControllerTest {
    private final GetBankConnectionsUseCase connections = mock(GetBankConnectionsUseCase.class);
    private final GetBankConnectionAccountsUseCase candidates = mock(GetBankConnectionAccountsUseCase.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new BankConnectionQueryController(connections, candidates))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void listsPublicMetadataWithoutConnectedIdOrCredentials() throws Exception {
        when(connections.getConnections()).thenReturn(List.of(new BankConnectionSummaryResult(
                7L, "0020", "우리은행", "icon", ExternalConnectionStatus.CONNECTED,
                LocalDateTime.of(2026, 10, 11, 0, 0), 0)));
        mvc.perform(get("/api/v2/accounts/connections"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].connectionId").value(7))
                .andExpect(jsonPath("$.data[0].linkedAccountCount").value(0))
                .andExpect(jsonPath("$.data[0].connectedId").doesNotExist())
                .andExpect(jsonPath("$.data[0].password").doesNotExist());
    }

    @Test
    void queriesExistingConnectionWithoutCreatingOne() throws Exception {
        var query = new GetBankConnectionAccountsQuery(7L, "0020", "19990101");
        when(candidates.getConnectionAccounts(query)).thenReturn(new BankConnectionResult(
                7L, "0020", ExternalConnectionStatus.CONNECTED, List.of()));
        mvc.perform(get("/api/v2/accounts/connections/7/accounts")
                        .param("organization", "0020").param("birthDate", "19990101"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.candidates").isEmpty());
        verify(candidates).getConnectionAccounts(query);
    }

    @Test
    void invalidIdsBankCodesAndBirthDatesAreRejectedBeforeUseCase() throws Exception {
        mvc.perform(get("/api/v2/accounts/connections/0/accounts").param("organization", "0020"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/accounts/connections/7/accounts").param("organization", "bank"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v2/accounts/connections/7/accounts")
                        .param("organization", "0020").param("birthDate", "private-value"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(candidates);
    }
}
