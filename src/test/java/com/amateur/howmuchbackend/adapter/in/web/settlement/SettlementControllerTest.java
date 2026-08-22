package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantAvatarUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.CreateSettlementCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDraftCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 Web API 계약")
class SettlementControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString("44c9323d-6a65-4a93-aeaa-ef8f24341c8a");
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
                getSettlementParticipantAvatarUseCase
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
