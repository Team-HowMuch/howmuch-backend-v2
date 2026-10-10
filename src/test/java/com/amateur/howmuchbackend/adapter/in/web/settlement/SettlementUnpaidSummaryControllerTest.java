package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUnpaidSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementUnpaidSummaryResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettlementUnpaidSummaryControllerTest {
    @Mock private GetSettlementUnpaidSummaryUseCase useCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new SettlementUnpaidSummaryController(useCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unpaidSummary_request_returnsNumericFields() throws Exception {
        when(useCase.getSummary()).thenReturn(new SettlementUnpaidSummaryResult(7_000, 1, 2));

        mvc.perform(get("/api/v2/settlements/unpaid-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.remainingReceivableAmount").value(7_000))
                .andExpect(jsonPath("$.data.remainingReceivableAmount").isNumber())
                .andExpect(jsonPath("$.data.unpaidParticipantCount").value(1))
                .andExpect(jsonPath("$.data.unpaidParticipantCount").isNumber())
                .andExpect(jsonPath("$.data.unconfirmedSettlementCount").value(2))
                .andExpect(jsonPath("$.data.unconfirmedSettlementCount").isNumber())
                .andExpect(jsonPath("$.error").doesNotExist());
        verify(useCase).getSummary();
    }

    @Test
    void unpaidSummary_empty_returnsPresentZeroFields() throws Exception {
        when(useCase.getSummary()).thenReturn(new SettlementUnpaidSummaryResult(0, 0, 0));

        mvc.perform(get("/api/v2/settlements/unpaid-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.remainingReceivableAmount").value(0))
                .andExpect(jsonPath("$.data.unpaidParticipantCount").value(0))
                .andExpect(jsonPath("$.data.unconfirmedSettlementCount").value(0));
    }
}
