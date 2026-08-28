package com.amateur.howmuchbackend.adapter.in.web.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerChatUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerRecommendationsUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerChatResult;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerRecommendationsResult;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerSummaryResult;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerActionType;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerView;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 총무 Web API 계약")
class TreasurerControllerTest {
    @Mock
    private GetTreasurerChatUseCase getTreasurerChatUseCase;
    @Mock
    private GetTreasurerSummaryUseCase getTreasurerSummaryUseCase;
    @Mock
    private AskTreasurerUseCase askTreasurerUseCase;
    @Mock
    private GetTreasurerRecommendationsUseCase getTreasurerRecommendationsUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new TreasurerController(
                        getTreasurerChatUseCase,
                        getTreasurerSummaryUseCase,
                        askTreasurerUseCase,
                        getTreasurerRecommendationsUseCase
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("현황 요약을 반환한다")
    void returnsSummary() throws Exception {
        when(getTreasurerSummaryUseCase.getSummary()).thenReturn(new TreasurerSummaryResult(
                30_000L, 1, 0L, 0, 1, 0, "보낼 정산 1건, 30,000원이 남아 있어요."
        ));

        mockMvc.perform(get("/api/v2/treasurer/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sendAmount").value(30_000))
                .andExpect(jsonPath("$.data.message").value("보낼 정산 1건, 30,000원이 남아 있어요."));
    }

    @Test
    @DisplayName("질의 답변을 반환한다")
    void returnsAnswer() throws Exception {
        when(askTreasurerUseCase.ask(any())).thenReturn(new TreasurerAnswerResult(
                InquiryIntent.SEND_SETTLEMENTS,
                InquiryPeriod.THIS_MONTH,
                "이번 달 기준으로 조회했어요.",
                TreasurerAnswerView.LIST,
                "이번 달 기준으로 조회했어요. 보낼 정산은 1건, 총 45,000원이 남아 있어요.",
                "누르면 해당되는 정산 상세로 이동해요!",
                List.of(),
                null,
                List.of(new TreasurerAnswerResult.Action(TreasurerAnswerActionType.SEND_LIST, "보낼 정산 확인하기")),
                List.of()
        ));

        mockMvc.perform(post("/api/v2/treasurer/questions")
                        .contentType("application/json")
                        .content("""
                                {"question":"이번 달 내가 보낼 정산은 얼마야?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.intent").value("SEND_SETTLEMENTS"))
                .andExpect(jsonPath("$.data.actions[0].label").value("보낼 정산 확인하기"));
    }

    @Test
    @DisplayName("채팅 시작 인사와 바로가기를 반환한다")
    void returnsChatStart() throws Exception {
        when(getTreasurerChatUseCase.getChat()).thenReturn(new TreasurerChatResult(
                "안녕하세요. 어떤 정산이 궁금하세요?",
                List.of(new TreasurerChatResult.Prompt(InquiryIntent.SEND_SETTLEMENTS, "보낼 정산", "보낼 정산")),
                List.of(new TreasurerChatResult.Prompt(InquiryIntent.GUIDE, "OCR 정산은 어떻게 해?", "OCR 정산은 어떻게 해?"))
        ));

        mockMvc.perform(get("/api/v2/treasurer/chat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.greeting").value("안녕하세요. 어떤 정산이 궁금하세요?"))
                .andExpect(jsonPath("$.data.quickActions[0].label").value("보낼 정산"));
    }

    @Test
    @DisplayName("빈 질문은 400을 반환한다")
    void rejectsBlankQuestion() throws Exception {
        mockMvc.perform(post("/api/v2/treasurer/questions")
                        .contentType("application/json")
                        .content("""
                                {"question":" "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("추천 목록을 반환한다")
    void returnsRecommendations() throws Exception {
        when(getTreasurerRecommendationsUseCase.getRecommendations())
                .thenReturn(new TreasurerRecommendationsResult("처리할 정산이 없어요.", List.of()));

        mockMvc.perform(get("/api/v2/treasurer/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("처리할 정산이 없어요."));
    }
}
