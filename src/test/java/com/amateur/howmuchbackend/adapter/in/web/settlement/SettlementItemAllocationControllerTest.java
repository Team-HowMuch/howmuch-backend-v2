package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SaveSettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SubmitSettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationPhase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemSelectionResult;
import com.amateur.howmuchbackend.domain.settlement.SettlementItemAllocationFraction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("공개 품목 배분 Web API 계약")
class SettlementItemAllocationControllerTest {
    private static final String TOKEN = "x".repeat(43);
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final UUID PARTICIPANT_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final UUID CONTEXT_ID = UUID.randomUUID();
    private static final String BASE_PATH =
            "/api/v2/settlement-links/{token}/participants/{participantId}";

    @Mock private GetSettlementItemAllocationUseCase getUseCase;
    @Mock private SaveSettlementItemSelectionUseCase saveUseCase;
    @Mock private RemoveSettlementItemSelectionUseCase removeUseCase;
    @Mock private SubmitSettlementItemAllocationUseCase submitUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SettlementItemAllocationController(
                        getUseCase,
                        saveUseCase,
                        removeUseCase,
                        submitUseCase
                )
        ).build();
    }

    @Test
    @DisplayName("참여 문맥 헤더로 품목·선택·예정 금액을 조회한다")
    void itemAllocation_contextRequest_returnsSelectionAndAmounts() throws Exception {
        when(getUseCase.get(TOKEN, CONTEXT_ID, PARTICIPANT_ID))
                .thenReturn(result(SettlementItemAllocationPhase.SELECTING));

        mockMvc.perform(get(BASE_PATH + "/items", TOKEN, PARTICIPANT_ID)
                        .header("Link-Participant-Context", CONTEXT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("SELECTING"))
                .andExpect(jsonPath("$.data.personalAmount").value(500))
                .andExpect(jsonPath("$.data.items[0].allocatedUnits").value(6))
                .andExpect(jsonPath("$.data.items[0].selections[0].fraction")
                        .value("HALF"));
    }

    @Test
    @DisplayName("품목 비율 선택을 Application Command로 변환한다")
    void savesSelection() throws Exception {
        when(saveUseCase.save(
                eq(TOKEN),
                eq(CONTEXT_ID),
                eq(PARTICIPANT_ID),
                eq(ITEM_ID),
                any()
        )).thenReturn(result(SettlementItemAllocationPhase.SELECTING));

        mockMvc.perform(put(BASE_PATH + "/items/{itemId}", TOKEN, PARTICIPANT_ID, ITEM_ID)
                        .header("Link-Participant-Context", CONTEXT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fraction\":\"HALF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].selections[0].amount").value(500));
    }

    @Test
    @DisplayName("선택 취소와 제출 endpoint를 공개한다")
    void removesAndSubmitsSelection() throws Exception {
        when(removeUseCase.remove(TOKEN, CONTEXT_ID, PARTICIPANT_ID, ITEM_ID))
                .thenReturn(result(SettlementItemAllocationPhase.SELECTING));
        when(submitUseCase.submit(TOKEN, CONTEXT_ID, PARTICIPANT_ID))
                .thenReturn(result(SettlementItemAllocationPhase.WAITING_FOR_OTHERS));

        mockMvc.perform(delete(
                        BASE_PATH + "/items/{itemId}", TOKEN, PARTICIPANT_ID, ITEM_ID
                ).header("Link-Participant-Context", CONTEXT_ID))
                .andExpect(status().isOk());
        mockMvc.perform(post(
                        BASE_PATH + "/item-submission", TOKEN, PARTICIPANT_ID
                ).header("Link-Participant-Context", CONTEXT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("WAITING_FOR_OTHERS"));
    }

    @Test
    @DisplayName("품목 선택 비율이 누락되면 400을 반환한다")
    void rejectsMissingFraction() throws Exception {
        mockMvc.perform(put(BASE_PATH + "/items/{itemId}", TOKEN, PARTICIPANT_ID, ITEM_ID)
                        .header("Link-Participant-Context", CONTEXT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private SettlementItemAllocationResult result(SettlementItemAllocationPhase phase) {
        return new SettlementItemAllocationResult(
                SETTLEMENT_ID,
                PARTICIPANT_ID,
                "최수아",
                phase,
                500,
                phase == SettlementItemAllocationPhase.SELECTING ? 0 : 1,
                2,
                List.of(new SettlementItemAllocationItemResult(
                        ITEM_ID,
                        "피자",
                        1,
                        1_000,
                        6,
                        12,
                        List.of(new SettlementItemSelectionResult(
                                PARTICIPANT_ID,
                                "최수아",
                                SettlementItemAllocationFraction.HALF,
                                1,
                                500
                        ))
                )),
                List.of()
        );
    }
}
