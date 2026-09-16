package com.amateur.howmuchbackend.application.receipt.service;

import com.amateur.howmuchbackend.application.port.in.receipt.AnalyzeReceiptUseCase;
import com.amateur.howmuchbackend.application.port.in.receipt.command.AnalyzeReceiptCommand;
import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.receipt.AnalyzeReceiptImagePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 영수증 이미지를 내부 OCR 서비스로 보내 분석 결과를 돌려준다.
 *
 * <p>트랜잭션을 열지 않는다. 아무것도 저장하지 않고, 한 건에 수 초가 걸리는 외부 호출을
 * 하는 동안 커넥션을 쥐고 있을 이유가 없다.
 */
@Service
@RequiredArgsConstructor
public class AnalyzeReceiptService implements AnalyzeReceiptUseCase {
    /**
     * OCR 서비스가 받는 형식. 그 외는 보내기 전에 막는다.
     *
     * <p>OCR 한 건은 수 초짜리 GPU 작업이고 장비는 한 대뿐이라, 읽지 못할 것이 뻔한
     * 요청으로 그 시간을 쓰지 않는다.
     */
    private static final List<String> SUPPORTED_CONTENT_TYPES = List.of("image/jpeg", "image/png");

    private final CurrentActorPort currentActorPort;
    private final AnalyzeReceiptImagePort analyzeReceiptImagePort;

    @Override
    public ReceiptAnalysisResult analyze(AnalyzeReceiptCommand command) {
        // 인증된 사용자만 내부 OCR 서비스에 닿는다. 값 자체는 쓰지 않지만
        // 여기서 읽어야 미인증 호출이 외부 서비스까지 가지 않는다.
        currentActorPort.getCurrentActor();

        requireAnalyzableImage(command);
        return analyzeReceiptImagePort.analyze(
                command.fileName(), command.contentType(), command.bytes());
    }

    private void requireAnalyzableImage(AnalyzeReceiptCommand command) {
        if (command == null || command.bytes() == null || command.bytes().length == 0) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
        }
        if (!isSupported(command.contentType())) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
        }
    }

    private boolean isSupported(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        // "image/jpeg; charset=..." 같은 파라미터가 붙어 올 수 있다.
        String bare = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_CONTENT_TYPES.contains(bare);
    }
}
