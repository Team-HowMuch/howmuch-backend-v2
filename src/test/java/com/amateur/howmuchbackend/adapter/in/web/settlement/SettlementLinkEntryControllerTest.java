package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.ResolveSettlementLinkUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementLinkParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 8, 24, 10, 0);
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 8, 24, 12, 30);

    @Mock private ResolveSettlementLinkUseCase resolveSettlementLinkUseCase;
    @Mock private SelectSettlementLinkParticipantUseCase selectSettlementLinkParticipantUseCase;

    @Mock private GetSettlementLinkParticipantAvatarUseCase getSettlementLinkParticipantAvatarUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SettlementLinkEntryController(
                resolveSettlementLinkUseCase,
                selectSettlementLinkParticipantUseCase,
                getSettlementLinkParticipantAvatarUseCase
        )).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("비로그인 링크 조회는 이름 선택 후보를 반환한다")
    void browserEntry_anonymousRequest_returnsCandidates() throws Exception {
        when(resolveSettlementLinkUseCase.resolve(TOKEN)).thenReturn(
                new SettlementLinkEntryResult(
                        SETTLEMENT_ID,
                        "회식 정산",
                        "행복식당",
                        LocalDateTime.of(2026, 8, 24, 19, 30),
                        REQUESTED_AT,
                        10_000,
                        2,
                        SettlementRequestPhase.TRANSFER_READY,
                        SettlementLinkEntryMode.BROWSER_SELECTION,
                        null,
                        List.of(new SettlementLinkParticipantResult(
                                PARTICIPANT_ID,
                                "민선영",
                                false,
                                6_000L,
                                "avatar-version"
                        ), new SettlementLinkParticipantResult(UUID.randomUUID(), "비회원", false, 0L, null)),
                        null,
                        null
                )
        );

        mockMvc.perform(get("/api/v2/settlement-links/{token}", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeName").value("행복식당"))
                .andExpect(jsonPath("$.data.paidAt").value("2026-08-24T19:30:00"))
                .andExpect(jsonPath("$.data.requestedAt").value("2026-08-24T10:00:00"))
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
                        .value(6_000))
                .andExpect(jsonPath("$.data.participantCandidates[0].avatarUrl").value(avatarUrl()))
                .andExpect(jsonPath("$.data.participantCandidates[1].avatarUrl").value(nullValue()));
    }

    @Test
    @DisplayName("브라우저 참여자 선택은 후속 API용 단기 참여 문맥을 반환한다")
    void browserParticipant_selection_returnsTemporaryContext() throws Exception {
        when(selectSettlementLinkParticipantUseCase.select(TOKEN, PARTICIPANT_ID)).thenReturn(
                new SettlementLinkParticipantSelectionResult(
                        SETTLEMENT_ID,
                        new SettlementLinkParticipantResult(
                                PARTICIPANT_ID,
                                "민선영",
                                false,
                                6_000L,
                                "avatar-version"
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
                .andExpect(jsonPath("$.data.participant.avatarUrl").value(avatarUrl()))
                .andExpect(jsonPath("$.data.nextPhase").value("TRANSFER_READY"))
                .andExpect(jsonPath("$.data.participantContext")
                        .value("074dc83c-f0aa-4bf6-8f6b-d17a415620c7"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-08-24T12:30:00"));
    }
    @Test
    @DisplayName("APP_DIRECT의 selectedParticipant에도 공개 링크 아바타 URL을 제공한다")
    void appDirect_returnsSelectedAvatarUrl() throws Exception {
        when(resolveSettlementLinkUseCase.resolve(TOKEN)).thenReturn(new SettlementLinkEntryResult(
                SETTLEMENT_ID, "정산", null, null, REQUESTED_AT, 10_000, 2,
                SettlementRequestPhase.TRANSFER_READY, SettlementLinkEntryMode.APP_DIRECT,
                new SettlementLinkParticipantResult(PARTICIPANT_ID, "회원", false, 6_000L, "avatar-version"),
                List.of(), "context", EXPIRES_AT));

        mockMvc.perform(get("/api/v2/settlement-links/{token}", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedParticipant.avatarUrl").value(avatarUrl()));
    }

    @Test
    @DisplayName("이미지는 로그인·참여 문맥 없이 조회하며 캐시에 남기지 않는다")
    void avatar_anonymousRequest_returnsImageWithoutCaching() throws Exception {
        byte[] image = {1, 2, 3};
        when(getSettlementLinkParticipantAvatarUseCase.get(TOKEN, PARTICIPANT_ID))
                .thenReturn(new ProfileImage("image/png", image));

        mockMvc.perform(get("/api/v2/settlement-links/{token}/participants/{participantId}/avatar",
                        TOKEN, PARTICIPANT_ID).param("v", "avatar-version"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(image))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("사용 불가 링크와 사진 없음은 이미지 API에서도 기존 404를 반환한다")
    void avatar_unavailableLinkOrImage_returns404() throws Exception {
        when(getSettlementLinkParticipantAvatarUseCase.get(TOKEN, PARTICIPANT_ID))
                .thenThrow(ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND))
                .thenThrow(ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/v2/settlement-links/{token}/participants/{participantId}/avatar",
                            TOKEN, PARTICIPANT_ID))
                    .andExpect(status().isNotFound());
        }
    }

    private String avatarUrl() {
        return "/api/v2/settlement-links/" + TOKEN + "/participants/" + PARTICIPANT_ID + "/avatar?v=avatar-version";
    }

}
