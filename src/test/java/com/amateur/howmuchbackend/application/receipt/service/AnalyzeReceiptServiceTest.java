package com.amateur.howmuchbackend.application.receipt.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.receipt.command.AnalyzeReceiptCommand;
import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.receipt.AnalyzeReceiptImagePort;
import com.amateur.howmuchbackend.application.receipt.policy.ReceiptImagePolicy;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("영수증 이미지 분석 서비스")
class AnalyzeReceiptServiceTest {
    private static final byte[] JPEG = withSignature(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    private static final byte[] PNG = withSignature(
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

    /** 정책이 선언한 형식이 아니라 바이트를 보므로 실제 시그니처가 필요하다. */
    private static byte[] withSignature(byte[] signature) {
        byte[] content = new byte[64];
        System.arraycopy(signature, 0, content, 0, signature.length);
        return content;
    }

    @Mock
    private CurrentActorPort currentActorPort;

    @Mock
    private AnalyzeReceiptImagePort analyzeReceiptImagePort;

    /**
     * 인증 확인을 느슨한(lenient) 스텁으로 깔아 두면 서비스에서 그 줄을 지워도 테스트가
     * 그대로 통과한다. 엄격한 스텁만 쓰고, 순서는 아래에서 따로 못 박는다.
     *
     * <p>정책은 목이 아니라 실제 구현을 쓴다. 바이트 판정이 이 서비스의 계약이라,
     * 목으로 바꾸면 "무엇이든 통과"가 되어 검증이 사라진다.
     */
    private AnalyzeReceiptService service() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(1L));
        return new AnalyzeReceiptService(
                currentActorPort, new ReceiptImagePolicy(), analyzeReceiptImagePort);
    }

    @Test
    @DisplayName("이미지를 분석 포트에 넘기고 결과를 그대로 돌려준다")
    void delegatesToThePort() {
        ReceiptAnalysisResult analysis = new ReceiptAnalysisResult(
                "가게", "2026-09-16 12:00",
                List.of(new ReceiptAnalysisResult.Item("김밥", 1, 3_500L)),
                3_500L, "카드", true);
        when(analyzeReceiptImagePort.analyze("a.jpg", "image/jpeg", JPEG)).thenReturn(analysis);

        assertThat(service().analyze(new AnalyzeReceiptCommand("a.jpg", "image/jpeg", JPEG)))
                .isSameAs(analysis);
    }

    @Test
    @DisplayName("OCR 호출 전에 현재 사용자를 먼저 확인한다")
    void checksTheActorBeforeReachingOcr() {
        when(analyzeReceiptImagePort.analyze(any(), any(), any())).thenReturn(
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        service().analyze(new AnalyzeReceiptCommand("a.jpg", "image/jpeg", JPEG));

        InOrder order = inOrder(currentActorPort, analyzeReceiptImagePort);
        order.verify(currentActorPort).getCurrentActor();
        order.verify(analyzeReceiptImagePort).analyze(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/heic", "text/plain", "image/", " ", "image/png"})
    @DisplayName("선언한 형식이 무엇이든 실제 바이트가 JPEG면 JPEG로 보낸다")
    void ignoresTheDeclaredContentType(String declared) {
        // 멀티파트 Content-Type은 클라이언트가 정하는 값이다. 그대로 실어 보내면
        // OCR 서비스가 엉뚱한 형식으로 읽는다.
        when(analyzeReceiptImagePort.analyze(any(), eq("image/jpeg"), any())).thenReturn(
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        service().analyze(new AnalyzeReceiptCommand("a", declared, JPEG));
    }

    @Test
    @DisplayName("PNG 바이트는 image/png로 보낸다")
    void sendsDetectedPngType() {
        when(analyzeReceiptImagePort.analyze(any(), eq("image/png"), any())).thenReturn(
                new ReceiptAnalysisResult(null, null, List.of(), null, null, false));

        service().analyze(new AnalyzeReceiptCommand("a.png", "image/jpeg", PNG));
    }

    @Test
    @DisplayName("image/jpeg라고 선언한 PDF는 OCR까지 보내지 않는다")
    void rejectsDisguisedPdfBeforeCallingOcr() {
        // OCR 한 건은 수 초짜리 GPU 작업이다. 읽지 못할 것이 뻔한 요청을 보내면 안 된다.
        byte[] pdf = "%PDF-1.7\nfake".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service().analyze(new AnalyzeReceiptCommand("a.jpg", "image/jpeg", pdf)))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
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
    @DisplayName("미인증이면 401 그대로 올리고 바이트를 보지도 않는다")
    void doesNotReachOcrWhenActorIsMissing() {
        // 실제 구현(SpringSecurityAdapter)이 던지는 예외다. 임의의 RuntimeException 으로
        // 검증하면 이 경로가 401이 아니라 500이 되어도 테스트가 통과한다.
        when(currentActorPort.getCurrentActor())
                .thenThrow(new ApplicationException(CommonStatusCode.UNAUTHORIZED));
        AnalyzeReceiptService service = new AnalyzeReceiptService(
                currentActorPort, new ReceiptImagePolicy(), analyzeReceiptImagePort);

        assertThatThrownBy(() -> service.analyze(new AnalyzeReceiptCommand("a.jpg", "image/jpeg", JPEG)))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(CommonStatusCode.UNAUTHORIZED);
        verifyNoInteractions(analyzeReceiptImagePort);
    }
}
