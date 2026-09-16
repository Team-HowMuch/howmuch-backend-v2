package com.amateur.howmuchbackend.adapter.out.receipt;

import com.amateur.howmuchbackend.adapter.out.receipt.dto.OcrReceiptApiResponse;
import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import com.amateur.howmuchbackend.application.port.out.receipt.AnalyzeReceiptImagePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.infra.receipt.config.ReceiptOcrProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 내부 OCR 서비스에 영수증 이미지를 보내고 결과를 받는다.
 *
 * <p>이미지는 요청 처리 중 메모리에만 있고 디스크에 남기지 않는다.
 */
@Slf4j
@Component
public class ReceiptOcrHttpAdapter implements AnalyzeReceiptImagePort {
    private static final String MULTIPART_FIELD = "file";
    private static final String FALLBACK_FILE_NAME = "receipt";

    private final RestClient restClient;
    private final ReceiptOcrProperties properties;

    public ReceiptOcrHttpAdapter(
            @Qualifier("receiptOcrRestClient") RestClient restClient,
            ReceiptOcrProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public ReceiptAnalysisResult analyze(String fileName, String contentType, byte[] bytes) {
        if (!properties.isConfigured()) {
            // 주소가 비어 있으면 연동이 꺼진 것이다. 사용자에게는 서비스에 닿지 못한 것과 같다.
            throw ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE);
        }

        OcrReceiptApiResponse response;
        try {
            response = restClient.post()
                    .uri(properties.endpoint())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart(fileName, contentType, bytes))
                    .retrieve()
                    .body(OcrReceiptApiResponse.class);
        } catch (RestClientResponseException exception) {
            // 요청은 닿았고 서비스가 거절했다. 이미지를 읽지 못한 것으로 본다.
            log.warn(
                    "Receipt OCR rejected the request. status={}",
                    exception.getStatusCode().value()
            );
            throw ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_FAILED, exception);
        } catch (RuntimeException exception) {
            // 연결 실패·시간 초과·응답 해석 실패. 사용자는 기다렸다 다시 시도해야 한다.
            log.warn(
                    "Receipt OCR call failed. failureType={}",
                    exception.getClass().getSimpleName()
            );
            throw ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE, exception);
        }

        if (response == null || !response.hasResult()) {
            // 서비스는 살아 있는데 이미지를 구조화하지 못했다.
            throw ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_FAILED);
        }
        return response.toResult();
    }

    private MultiValueMap<String, Object> multipart(String fileName, String contentType, byte[] bytes) {
        ByteArrayResource image = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                // 파일 이름이 없으면 일부 서버가 파트를 파일로 보지 않는다.
                return fileName == null || fileName.isBlank() ? FALLBACK_FILE_NAME : fileName;
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(MULTIPART_FIELD, image);
        return form;
    }
}
