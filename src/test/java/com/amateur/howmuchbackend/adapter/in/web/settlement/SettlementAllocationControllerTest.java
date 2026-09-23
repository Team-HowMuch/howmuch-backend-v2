package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementDutchPayAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ReplaceSettlementDutchPayAllocationsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementAllocationTypeUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDutchPayAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ReplaceSettlementDutchPayAllocationsCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementAllocationTypeCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.UpdateSettlementDutchPayAllocationCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementAllocationNextStep;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementAllocationTypeResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayParticipantResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDutchPayResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 배분 Web API 계약")
class SettlementAllocationControllerTest {
    private static final UUID SETTLEMENT_ID = UUID.fromString(
            "44c9323d-6a65-4a93-aeaa-ef8f24341c8a"
    );
    private static final UUID CREATOR_PARTICIPANT_ID = UUID.fromString(
            "09ac164a-35d6-4549-bff4-b7b4c375d2aa"
    );
    private static final UUID FRIEND_PARTICIPANT_ID = UUID.fromString(
            "bbcc9e4e-70df-4a6c-a0e6-4f319de1f89a"
    );

    @Mock
    private SelectSettlementAllocationTypeUseCase selectAllocationTypeUseCase;
    @Mock
    private GetSettlementDutchPayAllocationUseCase getDutchPayAllocationUseCase;
    @Mock
    private UpdateSettlementDutchPayAllocationUseCase updateDutchPayAllocationUseCase;
    @Mock
    private ReplaceSettlementDutchPayAllocationsUseCase replaceDutchPayAllocationsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        SettlementAllocationController controller = new SettlementAllocationController(
                selectSettlementAllocationTypeUseCase(),
                getDutchPayAllocationUseCase,
                updateDutchPayAllocationUseCase,
                replaceDutchPayAllocationsUseCase
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("배분 유형을 Command로 변환하고 다음 더치페이 단계를 반환한다")
    void allocationType_validSelection_mapsCommandAndReturnsNextStep() throws Exception {
        when(selectAllocationTypeUseCase.select(
                SETTLEMENT_ID,
                new SelectSettlementAllocationTypeCommand(SettlementAllocationType.DUTCH_PAY)
        )).thenReturn(new SettlementAllocationTypeResult(
                SETTLEMENT_ID,
                SettlementAllocationType.DUTCH_PAY,
                SettlementAllocationNextStep.REVIEW_DUTCH_PAY
        ));

        mockMvc.perform(put(
                        "/api/v2/settlements/{settlementId}/allocation-type",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {"allocationType":"DUTCH_PAY"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.allocationType").value("DUTCH_PAY"))
                .andExpect(jsonPath("$.data.nextStep").value("REVIEW_DUTCH_PAY"));
    }

    @Test
    @DisplayName("배분 유형 누락을 UseCase 호출 전에 거부한다")
    void allocationType_missing_returnsBadRequest() throws Exception {
        mockMvc.perform(put(
                        "/api/v2/settlements/{settlementId}/allocation-type",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(selectAllocationTypeUseCase);
    }

    @Test
    @DisplayName("더치페이 화면 복원에 필요한 참여자 금액과 합계 상태를 반환한다")
    void dutchPayAllocation_request_returnsAmountsAndTotal() throws Exception {
        when(getDutchPayAllocationUseCase.get(SETTLEMENT_ID)).thenReturn(result());

        mockMvc.perform(get(
                        "/api/v2/settlements/{settlementId}/dutch-pay-allocations",
                        SETTLEMENT_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(1_000))
                .andExpect(jsonPath("$.data.allocatedAmount").value(1_000))
                .andExpect(jsonPath("$.data.differenceAmount").value(0))
                .andExpect(jsonPath("$.data.balanced").value(true))
                .andExpect(jsonPath("$.data.participants[0].creator").value(true))
                .andExpect(jsonPath("$.data.participants[1].displayName").value("친구"));
    }

    @Test
    @DisplayName("0원은 허용하고 수정 뒤 최신 불균형 상태를 반환한다")
    void dutchPayAllocation_zeroAmount_updatesAndReturnsLatestState() throws Exception {
        SettlementDutchPayResult updated = new SettlementDutchPayResult(
                SETTLEMENT_ID,
                1_000,
                500,
                500,
                false,
                List.of(
                        new SettlementDutchPayParticipantResult(
                                CREATOR_PARTICIPANT_ID,
                                "생성자",
                                500,
                                true
                        ),
                        new SettlementDutchPayParticipantResult(
                                FRIEND_PARTICIPANT_ID,
                                "친구",
                                0,
                                false
                        )
                )
        );
        when(updateDutchPayAllocationUseCase.update(
                SETTLEMENT_ID,
                FRIEND_PARTICIPANT_ID,
                new UpdateSettlementDutchPayAllocationCommand(0)
        )).thenReturn(updated);

        mockMvc.perform(patch(
                        "/api/v2/settlements/{settlementId}/dutch-pay-allocations/{participantId}",
                        SETTLEMENT_ID,
                        FRIEND_PARTICIPANT_ID
                ).contentType("application/json")
                .content("""
                        {"amount":0}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocatedAmount").value(500))
                .andExpect(jsonPath("$.data.differenceAmount").value(500))
                .andExpect(jsonPath("$.data.balanced").value(false));
    }

    @Test
    @DisplayName("음수 금액을 UseCase 호출 전에 거부한다")
    void dutchPayAmount_negative_returnsBadRequest() throws Exception {
        mockMvc.perform(patch(
                        "/api/v2/settlements/{settlementId}/dutch-pay-allocations/{participantId}",
                        SETTLEMENT_ID,
                        FRIEND_PARTICIPANT_ID
                ).contentType("application/json")
                .content("""
                        {"amount":-1}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(updateDutchPayAllocationUseCase);
    }

    @Test
    @DisplayName("참여자 전원의 금액을 Command로 변환해 한 번에 저장한다")
    void dutchPayAllocations_allParticipants_savedOnce() throws Exception {
        when(replaceDutchPayAllocationsUseCase.replace(
                SETTLEMENT_ID,
                new ReplaceSettlementDutchPayAllocationsCommand(List.of(
                        new ReplaceSettlementDutchPayAllocationsCommand.Allocation(
                                CREATOR_PARTICIPANT_ID,
                                600
                        ),
                        new ReplaceSettlementDutchPayAllocationsCommand.Allocation(
                                FRIEND_PARTICIPANT_ID,
                                400
                        )
                ))
        )).thenReturn(result());

        mockMvc.perform(put(
                        "/api/v2/settlements/{settlementId}/dutch-pay-allocations",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {"allocations":[
                          {"participantId":"09ac164a-35d6-4549-bff4-b7b4c375d2aa","amount":600},
                          {"participantId":"bbcc9e4e-70df-4a6c-a0e6-4f319de1f89a","amount":400}
                        ]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocatedAmount").value(1_000))
                .andExpect(jsonPath("$.data.balanced").value(true));
    }

    @Test
    @DisplayName("빈 배분 목록을 UseCase 호출 전에 거부한다")
    void rejectsEmptyAllocations() throws Exception {
        mockMvc.perform(put(
                        "/api/v2/settlements/{settlementId}/dutch-pay-allocations",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {"allocations":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(replaceDutchPayAllocationsUseCase);
    }

    @Test
    @DisplayName("참여자 ID 누락을 UseCase 호출 전에 거부한다")
    void rejectsAllocationWithoutParticipantId() throws Exception {
        mockMvc.perform(put(
                        "/api/v2/settlements/{settlementId}/dutch-pay-allocations",
                        SETTLEMENT_ID
                ).contentType("application/json")
                .content("""
                        {"allocations":[{"amount":1000}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));

        verifyNoInteractions(replaceDutchPayAllocationsUseCase);
    }

    private SelectSettlementAllocationTypeUseCase selectSettlementAllocationTypeUseCase() {
        return selectAllocationTypeUseCase;
    }

    private SettlementDutchPayResult result() {
        return new SettlementDutchPayResult(
                SETTLEMENT_ID,
                1_000,
                1_000,
                0,
                true,
                List.of(
                        new SettlementDutchPayParticipantResult(
                                CREATOR_PARTICIPANT_ID,
                                "생성자",
                                500,
                                true
                        ),
                        new SettlementDutchPayParticipantResult(
                                FRIEND_PARTICIPANT_ID,
                                "친구",
                                500,
                                false
                        )
                )
        );
    }
}
