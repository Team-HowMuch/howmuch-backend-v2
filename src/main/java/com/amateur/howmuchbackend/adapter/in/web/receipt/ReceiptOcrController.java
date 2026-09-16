package com.amateur.howmuchbackend.adapter.in.web.receipt;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.receipt.docs.ReceiptOcrDocs;
import com.amateur.howmuchbackend.adapter.in.web.receipt.dto.response.ReceiptOcrWebResponse;
import com.amateur.howmuchbackend.application.port.in.receipt.AnalyzeReceiptUseCase;
import com.amateur.howmuchbackend.application.port.in.receipt.command.AnalyzeReceiptCommand;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/receipts")
public class ReceiptOcrController implements ReceiptOcrDocs {
    private final AnalyzeReceiptUseCase analyzeReceiptUseCase;

    @Override
    @PostMapping(value = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ReceiptOcrWebResponse> analyze(@RequestPart("file") MultipartFile file) {
        return ApiResponse.success(
                ReceiptOcrWebResponse.from(analyzeReceiptUseCase.analyze(toCommand(file))));
    }

    private AnalyzeReceiptCommand toCommand(MultipartFile file) {
        // 파트가 아예 없거나 이름이 다르면 여기까지 오지 않는다. 멀티파트 해석 단계에서
        // MissingServletRequestPartException 이 나고 GlobalExceptionHandler 가 400으로 답한다.
        // 한도 초과도 같은 단계에서 413으로 갈린다.
        if (file == null || file.isEmpty()) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_RECEIPT_IMAGE);
        }
        try {
            return new AnalyzeReceiptCommand(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException exception) {
            // 업로드 스트림을 읽지 못했다. 이미지 문제로 보고 OCR까지 보내지 않는다.
            throw ApplicationException.of(SettlementStatusCode.INVALID_RECEIPT_IMAGE, exception);
        }
    }
}
