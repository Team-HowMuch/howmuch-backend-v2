package com.amateur.howmuchbackend.adapter.in.web.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerChatUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerRecommendationsUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.ReportTreasurerAnswerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerReportResult;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerResult;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerChatResult;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerRecommendationsResult;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerSummaryResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.InquiryPeriod;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerActionType;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerView;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerCardMarker;
import com.amateur.howmuchbackend.domain.treasurer.status.TreasurerStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AI 비서 Web API 계약")
class TreasurerControllerTest {
    @Mock
    private GetTreasurerChatUseCase getTreasurerChatUseCase;
    @Mock
    private GetTreasurerSummaryUseCase getTreasurerSummaryUseCase;
    @Mock
    private AskTreasurerUseCase askTreasurerUseCase;
    @Mock
    private GetTreasurerRecommendationsUseCase getTreasurerRecommendationsUseCase;
    @Mock
    private ReportTreasurerAnswerUseCase reportTreasurerAnswerUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new TreasurerController(
                        getTreasurerChatUseCase,
                        getTreasurerSummaryUseCase,
                        askTreasurerUseCase,
                        getTreasurerRecommendationsUseCase,
                        reportTreasurerAnswerUseCase
                ))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("현황 요약을 반환한다")
    void treasurerSummary_request_returnsSummary() throws Exception {
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
    void treasurerAnswer_question_returnsAnswer() throws Exception {
        when(askTreasurerUseCase.ask(any())).thenReturn(answerResult());

        mockMvc.perform(post("/api/v2/treasurer/questions")
                        .contentType("application/json")
                        .content("""
                                {"question":"이번 달 내가 보낼 정산은 얼마야?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.intent").value("SEND_SETTLEMENTS"))
                .andExpect(jsonPath("$.data.actions[0].label").value("보낼 정산 확인하기"))
                .andExpect(jsonPath("$.data.items[0].allocationType").value("ITEM_ALLOCATION"))
                .andExpect(jsonPath("$.data.items[0].status").value("AWAITING_DEPOSIT"));
    }

    @Test
    @DisplayName("채팅 시작 인사와 바로가기를 반환한다")
    void treasurerChat_start_returnsGreetingAndShortcuts() throws Exception {
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
    void treasurerChat_blankQuestion_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v2/treasurer/questions")
                        .contentType("application/json")
                        .content("""
                                {"question":" "}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("답변 응답에 신고용 answerId를 싣는다")
    void treasurerAnswer_reportableResponse_includesAnswerId() throws Exception {
        when(askTreasurerUseCase.ask(any())).thenReturn(answerResult());

        mockMvc.perform(post("/api/v2/treasurer/questions")
                        .contentType("application/json")
                        .content("""
                                {"question":"보낼 정산"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerId").value("55555555-5555-5555-5555-555555555555"));
    }

    @Test
    @DisplayName("저장에 실패한 답변은 answerId가 null이다")
    void treasurerAnswer_saveFailure_returnsNullAnswerId() throws Exception {
        when(askTreasurerUseCase.ask(any())).thenReturn(answerResult().withAnswerId(null));

        mockMvc.perform(post("/api/v2/treasurer/questions")
                        .contentType("application/json")
                        .content("""
                                {"question":"보낼 정산"}
                                """))
                .andExpect(status().isOk())
                // doesNotExist()는 필드가 없어도 통과하므로, 신고 버튼을 숨기는 기준인 null을 직접 확인한다.
                .andExpect(jsonPath("$.data.answerId").value(nullValue()))
                .andExpect(content().string(containsString("\"answerId\":null")));
    }

    @Test
    @DisplayName("답변을 신고하면 접수 식별자와 시각을 반환한다")
    void answerReport_validRequest_returnsReceiptIdAndTime() throws Exception {
        when(reportTreasurerAnswerUseCase.report(any())).thenReturn(new TreasurerAnswerReportResult(
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                LocalDateTime.of(2026, 9, 15, 12, 0)
        ));

        mockMvc.perform(post("/api/v2/treasurer/answers/55555555-5555-5555-5555-555555555555/reports")
                        .contentType("application/json")
                        .content("""
                                {"reason":"INACCURATE","detail":"금액이 달라요."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportId").value("66666666-6666-6666-6666-666666666666"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-15T12:00:00"));
    }

    @Test
    @DisplayName("신고 사유가 없으면 400을 반환한다")
    void answerReport_missingReason_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v2/treasurer/answers/55555555-5555-5555-5555-555555555555/reports")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("알 수 없는 신고 사유는 400을 반환한다")
    void rejectsUnknownReason() throws Exception {
        mockMvc.perform(post("/api/v2/treasurer/answers/55555555-5555-5555-5555-555555555555/reports")
                        .contentType("application/json")
                        .content("""
                                {"reason":"SPAM"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("신고 상세가 500자를 넘으면 400을 반환한다")
    void rejectsTooLongDetail() throws Exception {
        mockMvc.perform(post("/api/v2/treasurer/answers/55555555-5555-5555-5555-555555555555/reports")
                        .contentType("application/json")
                        .content("{\"reason\":\"OTHER\",\"detail\":\"" + "가".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("answerId 형식이 잘못되면 400을 반환한다")
    void rejectsMalformedAnswerId() throws Exception {
        mockMvc.perform(post("/api/v2/treasurer/answers/not-a-uuid/reports")
                        .contentType("application/json")
                        .content("""
                                {"reason":"OTHER"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("본인 답변이 아니거나 없는 답변은 404를 반환한다")
    void returnsNotFoundForAnswerThatIsNotMine() throws Exception {
        when(reportTreasurerAnswerUseCase.report(any()))
                .thenThrow(new ApplicationException(TreasurerStatusCode.TREASURER_ANSWER_NOT_FOUND));

        mockMvc.perform(post("/api/v2/treasurer/answers/55555555-5555-5555-5555-555555555555/reports")
                        .contentType("application/json")
                        .content("""
                                {"reason":"OTHER"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TREASURER_ANSWER_NOT_FOUND"));
    }

    @Test
    @DisplayName("이미 신고한 답변은 409를 반환한다")
    void returnsConflictForDuplicateReport() throws Exception {
        when(reportTreasurerAnswerUseCase.report(any()))
                .thenThrow(new ApplicationException(TreasurerStatusCode.TREASURER_ANSWER_ALREADY_REPORTED));

        mockMvc.perform(post("/api/v2/treasurer/answers/55555555-5555-5555-5555-555555555555/reports")
                        .contentType("application/json")
                        .content("""
                                {"reason":"OTHER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TREASURER_ANSWER_ALREADY_REPORTED"));
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

    private TreasurerAnswerResult answerResult() {
        return new TreasurerAnswerResult(
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                InquiryIntent.SEND_SETTLEMENTS,
                InquiryPeriod.THIS_MONTH,
                "이번 달 기준으로 조회했어요.",
                TreasurerAnswerView.LIST,
                "이번 달 기준으로 조회했어요. 보낼 정산은 1건, 총 45,000원이 남아 있어요.",
                "누르면 해당되는 정산 상세로 이동해요!",
                List.of(new TreasurerAnswerResult.Item(
                        UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        "2026.08.10",
                        "45,000원 내야 함",
                        "요청자 요청 - 미납",
                        SettlementDirection.SEND,
                        45_000L,
                        TreasurerCardMarker.NONE,
                        SettlementAllocationType.ITEM_ALLOCATION,
                        SettlementStatus.AWAITING_DEPOSIT
                )),
                null,
                List.of(new TreasurerAnswerResult.Action(TreasurerAnswerActionType.SEND_LIST, "보낼 정산 확인하기")),
                List.of()
        );
    }
}
