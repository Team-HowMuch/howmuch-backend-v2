package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementTransferInstructionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementTransferInstructionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("공개 정산 송금 안내 Web API 계약")
class SettlementTransferInstructionControllerTest {
    private static final String TOKEN = "x".repeat(43);
    private static final UUID SETTLEMENT_ID = UUID.fromString("7c33b1fb-82f1-4a45-a834-a015a26b2ba6");
    private static final UUID PARTICIPANT_ID = UUID.fromString("e4049b4f-1c0a-4c63-b88e-3bdaee853679");
    private static final UUID CONTEXT_ID = UUID.fromString("074dc83c-f0aa-4bf6-8f6b-d17a415620c7");
    private static final UUID SHARE_ID = UUID.fromString("0d90a9cd-c9d6-49d0-9217-4c105b9fb3da");

    @Mock private GetSettlementTransferInstructionUseCase getUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new SettlementTransferInstructionController(getUseCase)
        ).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "supertoss://send?bank=%EC%9A%B0%EB%A6%AC%EC%9D%80%ED%96%89&accountNo=1002123456789&amount=6000",
            "https://howmuch-mock-send.vercel.app/send?bankCode=0020&bank=%EC%9A%B0%EB%A6%AC%EC%9D%80%ED%96%89&accountNo=1002123456789&amount=6000&receiver=%EC%B5%9C%EC%88%98%EC%95%84"
    })
    @DisplayName("참여 문맥 헤더로 조회한 Toss와 Mock URL을 변형 없이 전달한다")
    void getsTransferInstruction(String url) throws Exception {
        when(getUseCase.get(TOKEN, CONTEXT_ID)).thenReturn(
                new SettlementTransferInstructionResult(
                        SETTLEMENT_ID,
                        PARTICIPANT_ID,
                        SHARE_ID,
                        6_000,
                        "최수아",
                        "0020",
                        "우리은행",
                        "1002123456789",
                        List.of(new SettlementTransferInstructionResult.TransferLink(
                                "TOSS",
                                url
                        ))
                )
        );

        mockMvc.perform(get(
                        "/api/v2/settlement-links/{token}/transfer-instruction",
                        TOKEN
                ).header("Link-Participant-Context", CONTEXT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.settlementId").value(SETTLEMENT_ID.toString()))
                .andExpect(jsonPath("$.data.participantId").value(PARTICIPANT_ID.toString()))
                .andExpect(jsonPath("$.data.shareId").value(SHARE_ID.toString()))
                .andExpect(jsonPath("$.data.amount").value(6_000))
                .andExpect(jsonPath("$.data.recipientBankCode").value("0020"))
                .andExpect(jsonPath("$.data.recipientAccountNumber").value("1002123456789"))
                .andExpect(jsonPath("$.data.transferLinks[0].provider").value("TOSS"))
                .andExpect(jsonPath("$.data.transferLinks[0].url").value(
                        url
                ));
    }

    @Test
    @DisplayName("참여 문맥 헤더가 없으면 요청을 거부한다")
    void rejectsMissingParticipantContext() throws Exception {
        mockMvc.perform(get(
                        "/api/v2/settlement-links/{token}/transfer-instruction",
                        TOKEN
                ))
                .andExpect(status().isBadRequest());
    }
}
