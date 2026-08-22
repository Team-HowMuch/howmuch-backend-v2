package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SettlementController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("정산 금액 JSON 역직렬화 계약")
class SettlementAmountDeserializationTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateSettlementUseCase createSettlementUseCase;

    @MockitoBean
    private GetSettlementUseCase getSettlementUseCase;

    @MockitoBean
    private UpdateSettlementDraftUseCase updateSettlementDraftUseCase;

    @MockitoBean
    private AddSettlementParticipantUseCase addSettlementParticipantUseCase;

    @MockitoBean
    private RemoveSettlementParticipantUseCase removeSettlementParticipantUseCase;

    @MockitoBean
    private GetSettlementParticipantsUseCase getSettlementParticipantsUseCase;

    @MockitoBean
    private GetSettlementParticipantAvatarUseCase getSettlementParticipantAvatarUseCase;

    @MockitoBean
    private AuthTokenPort authTokenPort;

    @MockitoBean
    private AuthSessionPort authSessionPort;

    @Test
    @DisplayName("원 단위 총액이 조용히 절삭되지 않도록 생성 요청의 소수를 거부한다")
    void rejectsFractionalAmountOnCreate() throws Exception {
        mockMvc.perform(post("/api/v2/settlements")
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "회식 정산",
                                  "totalAmount": 10000.5
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("원 단위 총액이 조용히 절삭되지 않도록 수정 요청의 소수를 거부한다")
    void rejectsFractionalAmountOnUpdate() throws Exception {
        mockMvc.perform(patch("/api/v2/settlements/{settlementId}", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "totalAmount": 12000.5
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }
}
