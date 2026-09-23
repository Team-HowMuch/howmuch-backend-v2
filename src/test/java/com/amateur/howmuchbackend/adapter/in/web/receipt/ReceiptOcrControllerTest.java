package com.amateur.howmuchbackend.adapter.in.web.receipt;

import com.amateur.howmuchbackend.application.port.in.receipt.AnalyzeReceiptUseCase;
import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("영수증 OCR Web API 계약")
class ReceiptOcrControllerTest {
    @Mock
    private AnalyzeReceiptUseCase analyzeReceiptUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ReceiptOcrController(analyzeReceiptUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private MockMultipartFile image(byte[] bytes) {
        return new MockMultipartFile("file", "receipt.jpg", "image/jpeg", bytes);
    }

    @Test
    @DisplayName("분석 결과를 반환한다")
    void receiptAnalysis_request_returnsAnalysis() throws Exception {
        when(analyzeReceiptUseCase.analyze(any())).thenReturn(new ReceiptAnalysisResult(
                "스타필드", "2026-09-16 16:47",
                List.of(new ReceiptAnalysisResult.Item("참치김밥", 1, 3_500L)),
                60_000L, "카드", true));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeName").value("스타필드"))
                .andExpect(jsonPath("$.data.purchasedAt").value("2026-09-16 16:47"))
                .andExpect(jsonPath("$.data.totalAmount").value(60_000))
                .andExpect(jsonPath("$.data.paymentMethod").value("카드"))
                .andExpect(jsonPath("$.data.totalVerified").value(true))
                .andExpect(jsonPath("$.data.items[0].name").value("참치김밥"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.items[0].price").value(3_500));
    }

    @Test
    @DisplayName("읽지 못한 값은 null로 내보낸다")
    void exposesUnreadValuesAsNull() throws Exception {
        when(analyzeReceiptUseCase.analyze(any())).thenReturn(
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                // doesNotExist() 는 필드가 없을 때도 통과한다. 앱이 "값을 못 읽었다"를
                // 판단하는 기준이 null 이므로 실제로 null 인지를 본다.
                .andExpect(jsonPath("$.data.storeName").value(nullValue()))
                .andExpect(jsonPath("$.data.totalAmount").value(nullValue()))
                .andExpect(content().string(containsString("\"storeName\":null")))
                .andExpect(jsonPath("$.data.totalVerified").value(false))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @DisplayName("빈 파일은 400이고 분석까지 가지 않는다")
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/v2/receipts/ocr").file(image(new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_RECEIPT_IMAGE"));
        verifyNoInteractions(analyzeReceiptUseCase);
    }

    @Test
    @DisplayName("이미지를 읽지 못하면 422로 다시 촬영을 알린다")
    void returnsUnprocessableWhenOcrCannotRead() throws Exception {
        when(analyzeReceiptUseCase.analyze(any()))
                .thenThrow(ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_FAILED));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_OCR_FAILED"));
    }

    @Test
    @DisplayName("분석 서비스에 닿지 못하면 503으로 재시도를 알린다")
    void returnsServiceUnavailableWhenOcrIsDown() throws Exception {
        when(analyzeReceiptUseCase.analyze(any()))
                .thenThrow(ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_OCR_UNAVAILABLE"));
    }
}
