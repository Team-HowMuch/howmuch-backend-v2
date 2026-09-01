package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.ResolveSettlementLinkUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementLinkParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryMode;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkEntryResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementLinkParticipantSelectionResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementRequestPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("공개 정산 링크 Web API 계약")
class SettlementLinkEntryControllerTest {
    private static final String TOKEN = "x".repeat(43);
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 8, 24, 12, 30);

    @Mock private ResolveSettlementLinkUseCase resolveSettlementLinkUseCase;
    @Mock private SelectSettlementLinkParticipantUseCase selectSettlementLinkParticipantUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SettlementLinkEntryController(
                resolveSettlementLinkUseCase,
                selectSettlementLinkParticipantUseCase
        )).build();
    }

    @Test
    @DisplayName("비로그인 링크 조회는 이름 선택 후보를 반환한다")
    void resolvesBrowserEntry() throws Exception {
        when(resolveSettlementLinkUseCase.resolve(TOKEN)).thenReturn(
                new SettlementLinkEntryResult(
                        SETTLEMENT_ID,
                        "회식 정산",
                        10_000,
                        2,
                        SettlementRequestPhase.TRANSFER_READY,
                        SettlementLinkEntryMode.BROWSER_SELECTION,
                        null,
                        List.of(new SettlementLinkParticipantResult(
                                PARTICIPANT_ID,
                                "민선영",
                                false,
                                6_000L
                        )),
                        null,
                        null
                )
        );

        mockMvc.perform(get("/api/v2/settlement-links/{token}", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryMode").value("BROWSER_SELECTION"))
                .andExpect(jsonPath("$.data.totalAmount").value(10_000))
                .andExpect(jsonPath("$.data.participantCount").value(2))
                .andExpect(jsonPath("$.data.participantCandidates[0].participantId")
                        .value(PARTICIPANT_ID.toString()))
                .andExpect(jsonPath("$.data.participantCandidates[0].displayName")
                        .value("민선영"))
                .andExpect(jsonPath("$.data.participantCandidates[0].isRequester")
                        .value(false))
                .andExpect(jsonPath("$.data.participantCandidates[0].amount")
                        .value(6_000));
    }

    @Test
    @DisplayName("브라우저 참여자 선택은 후속 API용 단기 참여 문맥을 반환한다")
    void selectsParticipant() throws Exception {
        when(selectSettlementLinkParticipantUseCase.select(TOKEN, PARTICIPANT_ID)).thenReturn(
                new SettlementLinkParticipantSelectionResult(
                        SETTLEMENT_ID,
                        new SettlementLinkParticipantResult(
                                PARTICIPANT_ID,
                                "민선영",
                                false,
                                6_000L
                        ),
                        SettlementRequestPhase.TRANSFER_READY,
                        "074dc83c-f0aa-4bf6-8f6b-d17a415620c7",
                        EXPIRES_AT
                )
        );

        mockMvc.perform(post(
                        "/api/v2/settlement-links/{token}/participants/{participantId}/select",
                        TOKEN,
                        PARTICIPANT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participant.displayName").value("민선영"))
                .andExpect(jsonPath("$.data.participant.isRequester").value(false))
                .andExpect(jsonPath("$.data.participant.amount").value(6_000))
                .andExpect(jsonPath("$.data.nextPhase").value("TRANSFER_READY"))
                .andExpect(jsonPath("$.data.participantContext")
                        .value("074dc83c-f0aa-4bf6-8f6b-d17a415620c7"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-08-24T12:30:00"));
    }
}
