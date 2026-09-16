package com.amateur.howmuchbackend.application.receipt.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.receipt.command.AnalyzeReceiptCommand;
import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.receipt.AnalyzeReceiptImagePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("영수증 이미지 분석 서비스")
class AnalyzeReceiptServiceTest {
    private static final byte[] IMAGE = "bytes".getBytes(StandardCharsets.UTF_8);

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private AnalyzeReceiptImagePort analyzeReceiptImagePort;

    /**
     * 인증 확인을 스텁으로 느슨하게(lenient) 깔아 두면 서비스에서 그 줄을 지워도
     * 테스트가 그대로 통과한다. 여기서는 엄격한 스텁만 쓰고, 순서는 아래에서 따로 못 박는다.
     */
    private AnalyzeReceiptService service() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        return new AnalyzeReceiptService(currentActorPort, analyzeReceiptImagePort);
    }

    @Test
    @DisplayName("이미지를 분석 포트에 넘기고 결과를 그대로 돌려준다")
    void delegatesToThePort() {
        ReceiptAnalysisResult analysis = new ReceiptAnalysisResult(
                "가게", "2026-09-16 12:00",
                List.of(new ReceiptAnalysisResult.Item("김밥", 1, 3_500L)),
                3_500L, "카드", true);
        when(analyzeReceiptImagePort.analyze("a.jpg", "image/jpeg", IMAGE)).thenReturn(analysis);

        assertThat(service().analyze(new AnalyzeReceiptCommand("a.jpg", "image/jpeg", IMAGE)))
                .isSameAs(analysis);
    }

    @Test
    @DisplayName("OCR 호출 전에 현재 사용자를 먼저 확인한다")
    void checksTheActorBeforeReachingOcr() {
        when(analyzeReceiptImagePort.analyze(any(), any(), any())).thenReturn(
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        service().analyze(new AnalyzeReceiptCommand("a.jpg", "image/jpeg", IMAGE));

        InOrder order = inOrder(currentActorPort, analyzeReceiptImagePort);
        order.verify(currentActorPort).getCurrentActor();
        order.verify(analyzeReceiptImagePort).analyze(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "IMAGE/JPEG", "image/jpeg; charset=binary"})
    @DisplayName("JPEG·PNG는 파라미터가 붙어 있어도 받는다")
    void acceptsSupportedImageTypes(String contentType) {
        when(analyzeReceiptImagePort.analyze(any(), any(), any())).thenReturn(
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        service().analyze(new AnalyzeReceiptCommand("a.jpg", contentType, IMAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/heic", "text/plain", "image/", " "})
    @DisplayName("지원하지 않는 형식은 OCR까지 보내지 않는다")
    void rejectsUnsupportedTypesBeforeCallingOcr(String contentType) {
        assertThatThrownBy(() -> service().analyze(
                new AnalyzeReceiptCommand("a.pdf", contentType, IMAGE)))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
        // OCR 한 건은 수 초짜리 GPU 작업이다. 읽지 못할 것이 뻔한 요청을 보내면 안 된다.
        verifyNoInteractions(analyzeReceiptImagePort);
    }

    @Test
    @DisplayName("형식이 없으면 OCR까지 보내지 않는다")
    void rejectsMissingContentType() {
        assertThatThrownBy(() -> service().analyze(new AnalyzeReceiptCommand("a", null, IMAGE)))
                .isInstanceOf(ApplicationException.class);
        verifyNoInteractions(analyzeReceiptImagePort);
    }

    @Test
    @DisplayName("빈 이미지는 OCR까지 보내지 않는다")
    void rejectsEmptyImage() {
        assertThatThrownBy(() -> service().analyze(
                new AnalyzeReceiptCommand("a.jpg", "image/jpeg", new byte[0])))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
        verifyNoInteractions(analyzeReceiptImagePort);
    }

    @Test
    @DisplayName("미인증이면 401 그대로 올리고 OCR까지 보내지 않는다")
    void doesNotReachOcrWhenActorIsMissing() {
        // 실제 구현(SpringSecurityAdapter)이 던지는 예외다. 임의의 RuntimeException 으로
        // 검증하면 이 경로가 401이 아니라 500이 되어도 테스트가 통과한다.
        when(currentActorPort.getCurrentActor())
                .thenThrow(new ApplicationException(CommonStatusCode.UNAUTHORIZED));
        AnalyzeReceiptService service =
                new AnalyzeReceiptService(currentActorPort, analyzeReceiptImagePort);

        assertThatThrownBy(() -> service.analyze(
                new AnalyzeReceiptCommand("a.jpg", "image/jpeg", IMAGE)))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(CommonStatusCode.UNAUTHORIZED);
        verifyNoInteractions(analyzeReceiptImagePort);
    }
}
