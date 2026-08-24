package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.RequestSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 요청 발급 Web API 계약")
class SettlementRequestControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 9, 23, 10, 0);

    @Mock private RequestSettlementUseCase requestSettlementUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SettlementRequestController(requestSettlementUseCase)
        ).build();
    }

    @Test
    @DisplayName("공통 URL과 다음 배분 단계·정산 상태를 반환한다")
    void requestsSettlement() throws Exception {
        when(requestSettlementUseCase.request(SETTLEMENT_ID)).thenReturn(
                new SettlementRequestResult(
                        SETTLEMENT_ID,
                        "https://howmuch.example/settlements/token",
                        EXPIRES_AT,
                        SettlementRequestPhase.TRANSFER_READY,
                        SettlementStatus.AWAITING_DEPOSIT
                )
        );

        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/request",
                        SETTLEMENT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.settlementId").value(SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.data.url").value(
                        "https://howmuch.example/settlements/token"
                ))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-09-23T10:00:00"))
                .andExpect(jsonPath("$.data.allocationPhase").value("TRANSFER_READY"))
                .andExpect(jsonPath("$.data.status").value("AWAITING_DEPOSIT"));
    }
}
