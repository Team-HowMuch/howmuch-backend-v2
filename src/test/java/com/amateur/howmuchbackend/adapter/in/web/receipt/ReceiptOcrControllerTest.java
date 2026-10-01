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
                List.of(new ReceiptAnalysisResult.Item("참치김밥", 1, 3_500L, null,
                        List.of(new ReceiptAnalysisResult.Option("와사비 추가", 500L)))),
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
                .andExpect(jsonPath("$.data.items[0].price").value(3_500))
                .andExpect(jsonPath("$.data.items[0].subItems[0].name").value("와사비 추가"))
                .andExpect(jsonPath("$.data.items[0].subItems[0].price").value(500))
                .andExpect(jsonPath("$.data.items[0].sub_items").doesNotExist());
    }

    @Test
    @DisplayName("읽지 못한 값은 null로 내보낸다")
    void receiptAnalysis_unreadValues_returnsNull() throws Exception {
        when(analyzeReceiptUseCase.analyze(any())).thenReturn(
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                // 앱은 읽지 못한 필드를 생략하지 않고 null로 반환하므로 실제 null을 확인한다.
                .andExpect(jsonPath("$.data.storeName").value(nullValue()))
                .andExpect(jsonPath("$.data.totalAmount").value(nullValue()))
                .andExpect(content().string(containsString("\"storeName\":null")))
                .andExpect(jsonPath("$.data.totalVerified").value(false))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @DisplayName("배달비를 camelCase로 내보내고, 값이 없으면 null로 내보낸다")
    void receiptAnalysis_deliveryFee_returned() throws Exception {
        when(analyzeReceiptUseCase.analyze(any())).thenReturn(
                new ReceiptAnalysisResult("가게", null, List.of(), 12_000L, "카드", true, 0L, 3_000L),
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryFee").value(3_000))
                .andExpect(jsonPath("$.data.discount").value(0))
                .andExpect(jsonPath("$.data.delivery_fee").doesNotExist());

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"deliveryFee\":null")));
    }

    @Test
    @DisplayName("빈 파일은 400이고 분석까지 가지 않는다")
    void receiptUpload_emptyFile_returnsBadRequestWithoutAnalysis() throws Exception {
        mockMvc.perform(multipart("/api/v2/receipts/ocr").file(image(new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_RECEIPT_IMAGE"));
        verifyNoInteractions(analyzeReceiptUseCase);
    }

    @Test
    @DisplayName("이미지를 읽지 못하면 422로 다시 촬영을 알린다")
    void receiptAnalysis_unreadableImage_returnsUnprocessable() throws Exception {
        when(analyzeReceiptUseCase.analyze(any()))
                .thenThrow(ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_FAILED));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_OCR_FAILED"));
    }

    @Test
    @DisplayName("분석 서비스에 닿지 못하면 503으로 재시도를 알린다")
    void receiptAnalysis_unavailableService_returnsRetryResponse() throws Exception {
        when(analyzeReceiptUseCase.analyze(any()))
                .thenThrow(ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE));

        mockMvc.perform(multipart("/api/v2/receipts/ocr")
                        .file(image("bytes".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("RECEIPT_OCR_UNAVAILABLE"));
    }
}
