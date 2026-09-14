package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.DeleteSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantCandidateAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementGroupParticipantCandidatesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.MatchSettlementContactsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RenameSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ResumeSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SearchSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SuspendSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.AddSettlementParticipantCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.GetSettlementsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListStatusFilter;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SettlementListTab;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantListItemResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementParticipantCandidateResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementContactCandidateResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDetailResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementListItemResult;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipantSource;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 Web API 계약")
class SettlementControllerTest {
    private static final String IMAGE_VERSION = "image-version";
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
    private static final UUID PARTICIPANT_ID = UUID.fromString("09ac164a-35d6-4549-bff4-b7b4c375d2aa");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 21, 9, 0);

    @Mock
    private CreateSettlementUseCase createSettlementUseCase;

    @Mock
    private DeleteSettlementUseCase deleteSettlementUseCase;

    @Mock
    private GetSettlementUseCase getSettlementUseCase;

    @Mock
    private GetSettlementsUseCase getSettlementsUseCase;

    @Mock
    private UpdateSettlementDraftUseCase updateSettlementDraftUseCase;

    @Mock
    private AddSettlementParticipantUseCase addSettlementParticipantUseCase;

    @Mock
    private RemoveSettlementParticipantUseCase removeSettlementParticipantUseCase;

    @Mock
    private SuspendSettlementUseCase suspendSettlementUseCase;

    @Mock
    private ResumeSettlementUseCase resumeSettlementUseCase;

    @Mock
    private RenameSettlementParticipantUseCase renameSettlementParticipantUseCase;

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

    @Mock
    private GetSettlementGroupParticipantCandidatesUseCase getSettlementGroupParticipantCandidatesUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        SettlementController controller = new SettlementController(
            createSettlementUseCase,
                deleteSettlementUseCase,
            getSettlementUseCase,
            getSettlementsUseCase,
                updateSettlementDraftUseCase,
                addSettlementParticipantUseCase,
                removeSettlementParticipantUseCase,
                renameSettlementParticipantUseCase,
                suspendSettlementUseCase,
                resumeSettlementUseCase,
                getSettlementParticipantsUseCase,
                getSettlementParticipantAvatarUseCase,
                searchSettlementParticipantUseCase,
                getSettlementParticipantCandidateAvatarUseCase,
                matchSettlementContactsUseCase,
                getSettlementGroupParticipantCandidatesUseCase
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
    @DisplayName("생성자가 삭제 가능한 정산을 삭제한다")
    void deletesSettlement() throws Exception {
        mockMvc.perform(delete("/api/v2/settlements/{settlementId}", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
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
    @DisplayName("정산 상세에 생성자 표시 정보와 실제 납부 진행도를 반환한다")
    void getsSettlement() throws Exception {
        when(getSettlementUseCase.get(SETTLEMENT_ID)).thenReturn(detailResult());

        mockMvc.perform(get("/api/v2/settlements/{settlementId}", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.creatorName").value("최수아"))
                .andExpect(jsonPath("$.data.bankName").value("NH농협"))
                .andExpect(jsonPath("$.data.accountNumber").value("3510018989873"))
                .andExpect(jsonPath("$.data.institutionIconUrl")
                        .value("https://cdn.example.com/nh.png"))
                .andExpect(jsonPath("$.data.viewerRole").value("CREATOR"))
                .andExpect(jsonPath("$.data.paymentInfo.amount").value(10_000))
                .andExpect(jsonPath("$.data.paymentInfo.storeName").value("롯데리아"))
                .andExpect(jsonPath("$.data.paymentInfo.paidAt")
                        .value("2026-08-21T08:30:00"))
                .andExpect(jsonPath("$.data.paymentSummary.progressPercent").value(50))
                .andExpect(jsonPath("$.data.reminderNextAvailableAt")
                        .value("2026-08-21T09:00:00"))
                .andExpect(jsonPath("$.data.participants[0].linked").value(true))
                .andExpect(jsonPath("$.data.participants[0].avatarUrl").value(
                        "/api/v2/settlements/%s/participants/%s/avatar?v=%s".formatted(
                                SETTLEMENT_ID,
                                PARTICIPANT_ID,
                                IMAGE_VERSION
                        )
                ))
                .andExpect(jsonPath("$.data.participants[0].paymentStatus").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.data.creatorId").doesNotExist())
                .andExpect(jsonPath("$.data.receivingAccountId").doesNotExist());
    }

    @Test
    @DisplayName("목록 화면에 원본 상태와 숫자형 요약 필드를 반환한다")
    void getsSettlementList() throws Exception {
        when(getSettlementsUseCase.get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE,
                SettlementListStatusFilter.IN_PROGRESS,
                10L,
                null
        ))).thenReturn(List.of(new SettlementListItemResult(
                SETTLEMENT_ID,
                SettlementStatus.AWAITING_DEPOSIT,
                CREATED_AT,
                null,
                600L,
                SettlementAllocationType.DUTCH_PAY,
                3,
                2,
                0,
                null,
                null,
                List.of(new SettlementListItemResult.GroupSummary(
                        10L,
                        "여행 모임",
                        IMAGE_VERSION
                ))
        )));

        mockMvc.perform(get("/api/v2/settlements")
                .queryParam("tab", "RECEIVE")
                .queryParam("status", "IN_PROGRESS")
                .queryParam("groupId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].settlementId").value(SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.data[0].status").value("AWAITING_DEPOSIT"))
                .andExpect(jsonPath("$.data[0]").value(org.hamcrest.Matchers.hasEntry(
                        org.hamcrest.Matchers.equalTo("completedAt"), org.hamcrest.Matchers.nullValue())))
                .andExpect(jsonPath("$.data[0].totalAmount").value(600))
                .andExpect(jsonPath("$.data[0].participantCount").value(3))
                .andExpect(jsonPath("$.data[0].payableParticipantCount").value(2))
                .andExpect(jsonPath("$.data[0].completedParticipantCount").value(0))
                .andExpect(jsonPath("$.data[0].groups[0].groupId").value(10))
                .andExpect(jsonPath("$.data[0].groups[0].groupName").value("여행 모임"))
                .andExpect(jsonPath("$.data[0].groups[0].groupImageUrl")
                        .value("/api/v2/groups/10/image?v=" + IMAGE_VERSION));
    }

    @Test
    void bindsCompletedYearMonthAndReturnsCompletedAt() throws Exception {
        var month = java.time.YearMonth.of(2026, 9);
        var completedAt = LocalDateTime.of(2026, 9, 1, 0, 0);
        when(getSettlementsUseCase.get(new GetSettlementsCommand(
                SettlementListTab.PAY, SettlementListStatusFilter.COMPLETED, 10L, month)))
                .thenReturn(List.of(new SettlementListItemResult(
                        SETTLEMENT_ID, SettlementStatus.COMPLETED, CREATED_AT, completedAt,
                        null, SettlementAllocationType.DUTCH_PAY, null, null, null, 600L,
                        "요청자", List.of())));
        mockMvc.perform(get("/api/v2/settlements")
                        .queryParam("tab", "PAY").queryParam("status", "COMPLETED")
                        .queryParam("groupId", "10").queryParam("yearMonth", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].completedAt").value("2026-09-01T00:00:00"));
    }

    @Test
    void preservesDefaultsWhenOnlyYearMonthIsProvided() throws Exception {
        mockMvc.perform(get("/api/v2/settlements").queryParam("yearMonth", "2026-09"))
                .andExpect(status().isOk());
        verify(getSettlementsUseCase).get(new GetSettlementsCommand(
                SettlementListTab.RECEIVE, SettlementListStatusFilter.IN_PROGRESS,
                null, java.time.YearMonth.of(2026, 9)));
    }

    @Test
    void acceptsYearMonthRangeBoundaries() throws Exception {
        for (String value : List.of("0001-01", "9999-12")) {
            mockMvc.perform(get("/api/v2/settlements")
                            .queryParam("yearMonth", value).queryParam("status", "COMPLETED"))
                    .andExpect(status().isOk());
            verify(getSettlementsUseCase).get(new GetSettlementsCommand(
                    SettlementListTab.RECEIVE, SettlementListStatusFilter.COMPLETED,
                    null, java.time.YearMonth.parse(value)));
        }
    }

    @Test
    void rejectsInvalidYearMonth() throws Exception {
        for (String value : List.of("2026-13", "2026-00", "2026-9", "2026-09-01", "invalid",
                "+999999999-12", "+294277-01", "+10000-01", "-0001-01", "0000-01",
                "+02026-09", "", " 2026-09", "2026-09 ")) {
            mockMvc.perform(get("/api/v2/settlements").queryParam("yearMonth", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(getSettlementsUseCase);
    }

    @Test
    @DisplayName("그룹 필터의 잘못된 식별자를 INVALID_ARGUMENT로 거부한다")
    void rejectsNonPositiveSettlementListGroupId() throws Exception {
        mockMvc.perform(get("/api/v2/settlements").queryParam("groupId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(getSettlementsUseCase);
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
    @DisplayName("생성자가 요청 중인 정산을 수동 중단하고 최신 상태를 받는다")
    void suspendsSettlement() throws Exception {
        when(suspendSettlementUseCase.suspend(SETTLEMENT_ID)).thenReturn(
                new SettlementResult(
                        SETTLEMENT_ID,
                        1L,
                        "회식 정산",
                        10_000,
                        SettlementStatus.SUSPENDED,
                        CREATED_AT
                )
        );

        mockMvc.perform(post("/api/v2/settlements/{settlementId}/suspend", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("생성자가 중단된 정산을 재개하고 최신 상태를 받는다")
    void resumesSettlement() throws Exception {
        when(resumeSettlementUseCase.resume(SETTLEMENT_ID)).thenReturn(
                new SettlementResult(
                        SETTLEMENT_ID,
                        1L,
                        "회식 정산",
                        10_000,
                        SettlementStatus.REQUESTED,
                        CREATED_AT
                )
        );

        mockMvc.perform(post("/api/v2/settlements/{settlementId}/resume", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    @DisplayName("정산 수명주기 권한·상태 오류를 기존 Application 오류 계약으로 반환한다")
    void returnsSettlementLifecycleErrors() throws Exception {
        when(suspendSettlementUseCase.suspend(SETTLEMENT_ID))
                .thenThrow(ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        when(resumeSettlementUseCase.resume(SETTLEMENT_ID))
                .thenThrow(ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_STATUS_TRANSITION));
        doThrow(ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_DELETABLE))
                .when(deleteSettlementUseCase).delete(SETTLEMENT_ID);

        mockMvc.perform(post("/api/v2/settlements/{settlementId}/suspend", SETTLEMENT_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SETTLEMENT_ACCESS_DENIED"));
        mockMvc.perform(post("/api/v2/settlements/{settlementId}/resume", SETTLEMENT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_SETTLEMENT_STATUS_TRANSITION"));
        mockMvc.perform(delete("/api/v2/settlements/{settlementId}", SETTLEMENT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SETTLEMENT_NOT_DELETABLE"));
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
    @DisplayName("잠금 획득 실패를 재시도 가능한 503 TEMPORARILY_UNAVAILABLE로 반환한다")
    void returnsServiceUnavailableForLockFailure() throws Exception {
        when(updateSettlementDraftUseCase.update(eq(SETTLEMENT_ID), any(UpdateSettlementDraftCommand.class)))
                .thenThrow(new PessimisticLockingFailureException("lock timeout"));

        mockMvc.perform(patch("/api/v2/settlements/{settlementId}", SETTLEMENT_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "totalAmount": 20000
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(503))
                .andExpect(jsonPath("$.error.code").value("TEMPORARILY_UNAVAILABLE"));
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
                        IMAGE_VERSION
                ),
                new SettlementParticipantListItemResult(
                        UUID.fromString("f2878f10-77cc-4e85-94a5-343b414f69d7"),
                        SettlementParticipantSource.CONTACT,
                        null,
                        "비회원 연락처",
                        1,
                        null
                )
        ));

        mockMvc.perform(get("/api/v2/settlements/{settlementId}/participants", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].participantId").value(PARTICIPANT_ID.toString()))
                .andExpect(jsonPath("$.data[0].publicId").value("sua.choi"))
                .andExpect(jsonPath("$.data[0].displayOrder").value(0))
                .andExpect(jsonPath("$.data[0].avatarUrl").value(
                        "/api/v2/settlements/%s/participants/%s/avatar?v=%s".formatted(
                                SETTLEMENT_ID,
                                PARTICIPANT_ID,
                                IMAGE_VERSION
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
                .andExpect(header().string("Cache-Control", "max-age=86400, private"))
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
                        new SettlementParticipantCandidateResult(
                                "sua.alpha", "최수아", IMAGE_VERSION
                        ),
                        new SettlementParticipantCandidateResult("sua.beta", "후보", null)
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
                        "/api/v2/settlements/%s/participant-candidates/users/sua.alpha/avatar?v=%s"
                                .formatted(SETTLEMENT_ID, IMAGE_VERSION)
                ))
                .andExpect(jsonPath("$.data[0].userId").doesNotExist())
                .andExpect(jsonPath("$.data[0].avatarFileName").doesNotExist())
                .andExpect(jsonPath("$.data[1].avatarUrl").doesNotExist());
    }

    @Test
    @DisplayName("그룹 구성원 후보에 공개 정보와 보호된 아바타 URL만 반환한다")
    void getsSettlementGroupParticipantCandidates() throws Exception {
        when(getSettlementGroupParticipantCandidatesUseCase.get(SETTLEMENT_ID, 10L))
                .thenReturn(List.of(
                        new SettlementParticipantCandidateResult(
                                "group.member", "그룹 회원", IMAGE_VERSION
                        )
                ));

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/participant-candidates/groups/{groupId}/members",
                        SETTLEMENT_ID,
                        10L
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].publicId").value("group.member"))
                .andExpect(jsonPath("$.data[0].displayName").value("그룹 회원"))
                .andExpect(jsonPath("$.data[0].avatarUrl").value(
                        "/api/v2/settlements/%s/participant-candidates/users/group.member/avatar?v=%s"
                                .formatted(SETTLEMENT_ID, IMAGE_VERSION)
                ))
                .andExpect(jsonPath("$.data[0].userId").doesNotExist());
    }

    @Test
    @DisplayName("그룹 ID와 공개 ID를 그룹 참여자 추가 Command로 변환한다")
    void addsSettlementGroupParticipant() throws Exception {
        UUID participantId = UUID.fromString("3b9da522-552a-4168-b052-794be9dbbe53");
        when(addSettlementParticipantUseCase.add(
                SETTLEMENT_ID,
                new AddSettlementParticipantCommand(
                        SettlementParticipantSource.GROUP_MEMBER,
                        10L,
                        "group.member",
                        null,
                        null
                )
        )).thenReturn(new SettlementParticipantResult(
                participantId,
                SettlementParticipantSource.GROUP_MEMBER,
                "group.member",
                "그룹 회원",
                2
        ));

        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participants",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {
                          "source": "GROUP_MEMBER",
                          "groupId": 10,
                          "publicId": "group.member"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.participantId").value(participantId.toString()))
                .andExpect(jsonPath("$.data.source").value("GROUP_MEMBER"))
                .andExpect(jsonPath("$.data.publicId").value("group.member"))
                .andExpect(jsonPath("$.data.displayName").value("그룹 회원"));
    }

    @Test
    @DisplayName("양수가 아닌 그룹 ID는 UseCase 호출 없이 거부한다")
    void rejectsNonPositiveGroupIdForParticipantAdd() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participants",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {
                          "source": "GROUP_MEMBER",
                          "groupId": 0,
                          "publicId": "group.member"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(addSettlementParticipantUseCase);
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
                .andExpect(header().string("Cache-Control", "max-age=86400, private"))
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
                        IMAGE_VERSION,
                        false
                ),
                new SettlementContactCandidateResult(
                        "contact-2",
                        "비회원",
                        false,
                        null,
                        null,
                        null,
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
                        "/api/v2/settlements/%s/participant-candidates/users/contact.member/avatar?v=%s"
                                .formatted(SETTLEMENT_ID, IMAGE_VERSION)
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
    @DisplayName("null 연락처 항목은 UseCase 호출 없이 INVALID_ARGUMENT로 거부한다")
    void rejectsNullContactMatchingItem() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participant-candidates/contacts/match",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {
                          "contacts": [null]
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(matchSettlementContactsUseCase);
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

    @Test
    @DisplayName("길이 제한을 초과한 연락처 항목을 UseCase 호출 없이 거부한다")
    void rejectsOversizedContactMatchingFields() throws Exception {
        String oversizedContactId = "c".repeat(101);
        String oversizedDisplayName = "이".repeat(101);
        String oversizedPhoneNumber = "-".repeat(21) + "01011112222";

        assertInvalidContactFields(oversizedContactId, "연락처", "01011112222");
        assertInvalidContactFields("contact-1", oversizedDisplayName, "01011112222");
        assertInvalidContactFields("contact-1", "연락처", oversizedPhoneNumber);

        verifyNoInteractions(matchSettlementContactsUseCase);
    }

    private void assertInvalidContactFields(
            String contactId,
            String displayName,
            String phoneNumber
    ) throws Exception {
        mockMvc.perform(post(
                        "/api/v2/settlements/{settlementId}/participant-candidates/contacts/match",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {
                          "contacts": [
                            {
                              "contactId": "%s",
                              "displayName": "%s",
                              "phoneNumber": "%s"
                            }
                          ]
                        }
                        """.formatted(
                        contactId,
                        displayName,
                        phoneNumber
                )))
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

    @Test
    @DisplayName("참여자 본인에게는 사용 가능한 내 프로필 이미지 경로를 반환한다")
    void returnsSelfAvatarUrl() throws Exception {
        when(getSettlementUseCase.get(SETTLEMENT_ID)).thenReturn(
                detailResult(SettlementDetailResult.ViewerRole.PARTICIPANT, true)
        );
        mockMvc.perform(get("/api/v2/settlements/{settlementId}", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participants[0].avatarUrl")
                        .value("/api/v2/user/me/avatar?v=" + IMAGE_VERSION));
    }

    @Test
    @DisplayName("사진 없는 행은 avatarUrl 필드를 null로 반환한다")
    void returnsNullAvatarUrl() throws Exception {
        when(getSettlementUseCase.get(SETTLEMENT_ID)).thenReturn(
                detailResult(SettlementDetailResult.ViewerRole.CREATOR, false)
        );
        mockMvc.perform(get("/api/v2/settlements/{settlementId}", SETTLEMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participants[0]").value(
                        org.hamcrest.Matchers.hasEntry(org.hamcrest.Matchers.is("avatarUrl"), org.hamcrest.Matchers.nullValue())
                ));
    }

    private SettlementDetailResult detailResult() {
        return detailResult(SettlementDetailResult.ViewerRole.CREATOR, true);
    }

    private SettlementDetailResult detailResult(
            SettlementDetailResult.ViewerRole viewerRole,
            boolean hasAvatar
    ) {
        return new SettlementDetailResult(
                SETTLEMENT_ID,
                "회식 정산",
                10_000,
                null,
                SettlementStatus.AWAITING_DEPOSIT,
                CREATED_AT,
                viewerRole,
                "최수아",
                "NH농협",
                "3510018989873",
                "https://cdn.example.com/nh.png",
                new SettlementDetailResult.PaymentInfo(
                        10_000,
                        "롯데리아",
                        LocalDateTime.of(2026, 8, 21, 8, 30)
                ),
                new SettlementDetailResult.PaymentSummary(8_000, 4_000, 4_000, 50, 1, 1, 0),
                LocalDateTime.of(2026, 8, 21, 9, 0),
                List.of(new SettlementDetailResult.ParticipantPayment(
                        PARTICIPANT_ID,
                        "김참여",
                        true,
                        8_000L,
                        4_000,
                        SettlementDetailResult.PaymentStatus.PARTIALLY_PAID,
                        hasAvatar ? IMAGE_VERSION : null
                ))
        );
    }
}
