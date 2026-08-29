package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.RemindSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReminderResult;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 독촉 Web API 계약")
class SettlementReminderControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final UUID PARTICIPANT_ID = UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final LocalDateTime REMINDED_AT = LocalDateTime.of(2026, 8, 29, 9, 0);

    @Mock private RemindSettlementParticipantUseCase useCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SettlementReminderController(useCase)
        ).build();
    }

    @Test
    @DisplayName("정산과 참여자 UUID를 전달하고 다음 독촉 가능 시각을 반환한다")
    void remindsParticipant() throws Exception {
        when(useCase.remind(SETTLEMENT_ID, PARTICIPANT_ID)).thenReturn(
                new SettlementReminderResult(REMINDED_AT, REMINDED_AT.plusMinutes(30))
        );

        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participants/{participantId}/reminders",
                        SETTLEMENT_ID,
                        PARTICIPANT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.remindedAt").value("2026-08-29T09:00:00"))
                .andExpect(jsonPath("$.data.nextAvailableAt").value("2026-08-29T09:30:00"));

        verify(useCase).remind(SETTLEMENT_ID, PARTICIPANT_ID);
    }
}
