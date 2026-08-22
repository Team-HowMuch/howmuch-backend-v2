package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantCandidateAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.MatchSettlementContactsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SearchSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantListItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantCandidateResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementContactCandidateResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 Web API 계약")
class SettlementControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final UUID PARTICIPANT_ID = UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 21, 9, 0);

    @Mock
    private CreateSettlementUseCase createSettlementUseCase;

    @Mock
    private GetSettlementUseCase getSettlementUseCase;

    @Mock
    private UpdateSettlementDraftUseCase updateSettlementDraftUseCase;

    @Mock
    private AddSettlementParticipantUseCase addSettlementParticipantUseCase;

    @Mock
    private RemoveSettlementParticipantUseCase removeSettlementParticipantUseCase;

    @Mock
    private GetSettlementParticipantsUseCase getSettlementParticipantsUseCase;

    @Mock
    private GetSettlementParticipantAvatarUseCase getSettlementParticipantAvatarUseCase;

    @Mock
    private SearchSettlementParticipantUseCase searchSettlementParticipantUseCase;

    @Mock
    private GetSettlementParticipantCandidateAvatarUseCase getSettlementParticipantCandidateAvatarUseCase;

    @Mock
    private MatchSettlementContactsUseCase matchSettlementContactsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        SettlementController controller = new SettlementController(
                createSettlementUseCase,
                getSettlementUseCase,
                updateSettlementDraftUseCase,
                addSettlementParticipantUseCase,
                removeSettlementParticipantUseCase,
                getSettlementParticipantsUseCase,
                getSettlementParticipantAvatarUseCase,
                searchSettlementParticipantUseCase,
                getSettlementParticipantCandidateAvatarUseCase,
                matchSettlementContactsUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("생성된 정산을 후속 단계에서 식별할 수 있도록 UUID와 기본 정보를 반환한다")
    void createsSettlement() throws Exception {
        when(createSettlementUseCase.create(any(CreateSettlementCommand.class))).thenReturn(result());

        mockMvc.perform(post("/api/v2/settlements")
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "회식 정산",
                                  "totalAmount": 10000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.settlementId").value(SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.data.title").value("회식 정산"))
                .andExpect(jsonPath("$.data.totalAmount").value(10_000))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    @DisplayName("식별할 수 없는 정산이 생성되지 않도록 빈 제목을 INVALID_ARGUMENT로 거부한다")
    void rejectsBlankTitle() throws Exception {
        mockMvc.perform(post("/api/v2/settlements")
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "   ",
                                  "totalAmount": 10000
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("생성자가 정산 초안을 이어서 편집할 수 있도록 저장된 기본 정보를 반환한다")
    void getsSettlement() throws Exception {
        when(getSettlementUseCase.get(SETTLEMENT_ID)).thenReturn(result());

        mockMvc.perform(get("/api/v2/settlements/{settlementId}", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.creatorId").value(1))
                .andExpect(jsonPath("$.data.createdAt").value("2026-08-21T09:00:00"));
    }

    @Test
    @DisplayName("잘못된 정산 식별자를 서버 오류로 오해하지 않도록 INVALID_ARGUMENT로 거부한다")
    void rejectsInvalidSettlementId() throws Exception {
        mockMvc.perform(get("/api/v2/settlements/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("읽을 수 없는 JSON을 서버 오류로 오해하지 않도록 INVALID_ARGUMENT로 거부한다")
    void rejectsUnreadableJson() throws Exception {
        mockMvc.perform(post("/api/v2/settlements")
                        .contentType("application/json")
                        .content("{\"title\":\"회식 정산\",\"totalAmount\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("의미 없는 변경 요청을 막기 위해 수정 필드가 없는 PATCH를 INVALID_ARGUMENT로 거부한다")
    void rejectsPatchWithoutFields() throws Exception {
        mockMvc.perform(patch("/api/v2/settlements/{settlementId}", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("부분 수정 의도를 보존하도록 전달된 필드만 Command로 변환해 결과를 반환한다")
    void updatesSettlementDraft() throws Exception {
        SettlementResult updated = new SettlementResult(
                SETTLEMENT_ID,
                1L,
                "최종 회식 정산",
                10_000,
                SettlementStatus.DRAFT,
                CREATED_AT
        );
        when(updateSettlementDraftUseCase.update(
                eq(SETTLEMENT_ID),
                eq(new UpdateSettlementDraftCommand("최종 회식 정산", null))
        )).thenReturn(updated);

        mockMvc.perform(patch("/api/v2/settlements/{settlementId}", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "title": "최종 회식 정산"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.title").value("최종 회식 정산"))
                .andExpect(jsonPath("$.data.totalAmount").value(10_000));
    }

    @Test
    @DisplayName("클라이언트가 최신 상태를 다시 조회하도록 동시 수정 충돌을 409 CONCURRENT_UPDATE로 반환한다")
    void returnsConflictForConcurrentUpdate() throws Exception {
        when(updateSettlementDraftUseCase.update(eq(SETTLEMENT_ID), any(UpdateSettlementDraftCommand.class)))
                .thenThrow(new OptimisticLockingFailureException("stale settlement"));

        mockMvc.perform(patch("/api/v2/settlements/{settlementId}", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "totalAmount": 20000
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(409))
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_UPDATE"));
    }

    @Test
    @DisplayName("화면 재진입 시 공개 참여자 정보와 보호된 아바타 URL을 반환한다")
    void getsSettlementParticipants() throws Exception {
        when(getSettlementParticipantsUseCase.get(SETTLEMENT_ID)).thenReturn(List.of(
                new SettlementParticipantListItemResult(
                        PARTICIPANT_ID,
                        SettlementParticipantSource.REGISTERED_USER,
                        "sua.choi",
                        "최수아",
                        0,
                        true
                ),
                new SettlementParticipantListItemResult(
                        UUID.fromString("f2878f10-77cc-4e85-94a5-343b414f69d7"),
                        SettlementParticipantSource.CONTACT,
                        null,
                        "비회원 연락처",
                        1,
                        false
                )
        ));

        mockMvc.perform(get("/api/v2/settlements/{settlementId}/participants", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].participantId").value(PARTICIPANT_ID.toString()))
                .andExpect(jsonPath("$.data[0].publicId").value("sua.choi"))
                .andExpect(jsonPath("$.data[0].displayOrder").value(0))
                .andExpect(jsonPath("$.data[0].avatarUrl").value(
                        "/api/v2/settlements/%s/participants/%s/avatar".formatted(
                                SETTLEMENT_ID,
                                PARTICIPANT_ID
                        )
                ))
                .andExpect(jsonPath("$.data[0].linkedUserId").doesNotExist())
                .andExpect(jsonPath("$.data[0].phoneHash").doesNotExist())
                .andExpect(jsonPath("$.data[1].publicId").doesNotExist())
                .andExpect(jsonPath("$.data[1].avatarUrl").doesNotExist());
    }

    @Test
    @DisplayName("아바타 URL에서 원본 Content-Type과 바이트를 반환한다")
    void getsSettlementParticipantAvatar() throws Exception {
        byte[] imageContent = {(byte) 0x89, 0x50, 0x4E, 0x47};
        when(getSettlementParticipantAvatarUseCase.get(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenReturn(new ProfileImage("image/png", imageContent));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/participants/{participantId}/avatar",
                        SETTLEMENT_ID,
                        PARTICIPANT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(imageContent));
    }

    @Test
    @DisplayName("아바타가 없는 참여자는 PROFILE_IMAGE_NOT_FOUND로 반환한다")
    void returnsNotFoundForMissingParticipantAvatar() throws Exception {
        when(getSettlementParticipantAvatarUseCase.get(SETTLEMENT_ID, PARTICIPANT_ID))
                .thenThrow(ApplicationException.of(UserStatusCode.PROFILE_IMAGE_NOT_FOUND));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/participants/{participantId}/avatar",
                        SETTLEMENT_ID,
                        PARTICIPANT_ID
                ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PROFILE_IMAGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("공개 ID 후보 검색 결과에 공개 정보와 보호된 아바타 URL만 반환한다")
    void searchesSettlementParticipantCandidates() throws Exception {
        when(searchSettlementParticipantUseCase.searchByPublicId(eq(SETTLEMENT_ID), any()))
                .thenReturn(List.of(
                        new SettlementParticipantCandidateResult("sua.alpha", "최수아", true),
                        new SettlementParticipantCandidateResult("sua.beta", "후보", false)
                ));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/participant-candidates/users",
                        SETTLEMENT_ID
                ).param("publicIdPrefix", "sua"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].publicId").value("sua.alpha"))
                .andExpect(jsonPath("$.data[0].displayName").value("최수아"))
                .andExpect(jsonPath("$.data[0].avatarUrl").value(
                        "/api/v2/settlements/%s/participant-candidates/users/sua.alpha/avatar"
                                .formatted(SETTLEMENT_ID)
                ))
                .andExpect(jsonPath("$.data[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data[0].avatarFileName").doesNotExist())
                .andExpect(jsonPath("$.data[1].avatarUrl").doesNotExist());
    }

    @Test
    @DisplayName("빈 공개 ID 검색어는 UseCase 호출 없이 INVALID_ARGUMENT로 거부한다")
    void rejectsBlankParticipantCandidateSearch() throws Exception {
        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/participant-candidates/users",
                        SETTLEMENT_ID
                ).param("publicIdPrefix", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("후보 아바타 URL에서 원본 Content-Type과 바이트를 반환한다")
    void getsSettlementParticipantCandidateAvatar() throws Exception {
        byte[] imageContent = {(byte) 0x89, 0x50, 0x4E, 0x47};
        when(getSettlementParticipantCandidateAvatarUseCase.get(SETTLEMENT_ID, "sua.alpha"))
                .thenReturn(new ProfileImage("image/png", imageContent));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/participant-candidates/users/{publicId}/avatar",
                        SETTLEMENT_ID,
                        "sua.alpha"
                ))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(imageContent));
    }

    @Test
    @DisplayName("연락처 매칭 결과에 공개 정보와 기존 참여 여부만 반환한다")
    void matchesSettlementContacts() throws Exception {
        when(matchSettlementContactsUseCase.match(eq(SETTLEMENT_ID), any())).thenReturn(List.of(
                new SettlementContactCandidateResult(
                        "contact-1",
                        "주소록 회원",
                        true,
                        "contact.member",
                        "서비스 회원",
                        true,
                        false
                ),
                new SettlementContactCandidateResult(
                        "contact-2",
                        "비회원",
                        false,
                        null,
                        null,
                        false,
                        true
                )
        ));

        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participant-candidates/contacts/match",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {
                          "contacts": [
                            {
                              "contactId": "contact-1",
                              "displayName": "주소록 회원",
                              "phoneNumber": "010-1111-2222"
                            },
                            {
                              "contactId": "contact-2",
                              "displayName": "비회원",
                              "phoneNumber": "010-2222-3333"
                            }
                          ]
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].contactId").value("contact-1"))
                .andExpect(jsonPath("$.data[0].contactDisplayName").value("주소록 회원"))
                .andExpect(jsonPath("$.data[0].member").value(true))
                .andExpect(jsonPath("$.data[0].publicId").value("contact.member"))
                .andExpect(jsonPath("$.data[0].memberDisplayName").value("서비스 회원"))
                .andExpect(jsonPath("$.data[0].avatarUrl").value(
                        "/api/v2/settlements/%s/participant-candidates/users/contact.member/avatar"
                                .formatted(SETTLEMENT_ID)
                ))
                .andExpect(jsonPath("$.data[0].phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.data[0].phoneHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data[0].avatarFileName").doesNotExist())
                .andExpect(jsonPath("$.data[1].member").value(false))
                .andExpect(jsonPath("$.data[1].alreadyParticipant").value(true))
                .andExpect(jsonPath("$.data[1].publicId").doesNotExist());
    }

    @Test
    @DisplayName("빈 연락처 목록은 UseCase 호출 없이 INVALID_ARGUMENT로 거부한다")
    void rejectsEmptyContactMatchingRequest() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participant-candidates/contacts/match",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {
                          "contacts": []
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("휴대폰 번호가 비어 있는 연락처 항목을 INVALID_ARGUMENT로 거부한다")
    void rejectsContactWithoutPhoneNumber() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participant-candidates/contacts/match",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {
                          "contacts": [
                            {
                              "contactId": "contact-1",
                              "displayName": "연락처",
                              "phoneNumber": " "
                            }
                          ]
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    private SettlementResult result() {
        return new SettlementResult(
                SETTLEMENT_ID,
                1L,
                "회식 정산",
                10_000,
                SettlementStatus.DRAFT,
                CREATED_AT
        );
    }
}
