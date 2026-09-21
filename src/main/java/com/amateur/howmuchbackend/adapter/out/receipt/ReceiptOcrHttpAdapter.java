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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Set;

/**
 * 내부 OCR 서비스에 영수증 이미지를 보내고 결과를 받는다.
 *
 * <p>이미지는 우리 저장소(DB·오브젝트 스토리지)에 남기지 않는다. 다만 멀티파트 해석은
 * 서블릿 컨테이너가 하고, {@code spring.servlet.multipart.file-size-threshold} 기본값이
 * 0이라 업로드는 임시 파일로 먼저 떨어졌다가 요청이 끝날 때 삭제된다. "디스크에 전혀
 * 닿지 않는다"고 적으면 거짓말이 된다.
 */
@Slf4j
@Component
public class ReceiptOcrHttpAdapter implements AnalyzeReceiptImagePort {
    private static final String MULTIPART_FIELD = "file";
    private static final String FALLBACK_FILE_NAME = "receipt";

    /**
     * 서비스가 이미지를 **보고** 거절한 상태들. 이때만 재촬영을 안내한다.
     *
     * <p>목록에 없는 모든 상태는 재시도(503)로 보낸다. 화이트리스트를 반대로 두면
     * 새로 나타나는 상태가 조용히 재촬영 안내로 빠진다. 501·507이나 프록시 전용
     * 520 같은 값은 사진을 다시 찍어도 달라지지 않는데, 그걸 422로 내보내면 모든
     * 사용자가 모든 영수증에서 영원히 실패한다.
     *
     * <p>500이 여기 있는 것은 이 서비스의 계약이다. 분석 실패를
     * {@code 500 + {"ok": false, "error"}} 로 알린다.
     */
    private static final Set<Integer> IMAGE_REJECTED_STATUSES = Set.of(400, 413, 415, 422, 500);

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

        // 응답 해석과 결과 변환까지 한 try 안에 둔다. 밖으로 빼면 예상 못 한 본문 하나가
        // 그대로 500이 된다. 여기서 터지는 것은 전부 사용자가 손댈 수 없는 실패이므로
        // 재촬영(422)이 아니라 재시도(503)로 보낸다.
        try {
            OcrReceiptApiResponse response = restClient.post()
                    .uri(properties.endpoint())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart(fileName, contentType, bytes))
                    .retrieve()
                    .body(OcrReceiptApiResponse.class);

            if (response == null || !response.hasResult()) {
                // 서비스는 살아 있는데 이미지를 구조화하지 못했다.
                throw ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_FAILED);
            }
            return response.toResult();
        } catch (ApplicationException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw translate(exception);
        } catch (RuntimeException exception) {
            // 연결 실패·시간 초과·응답 본문 해석 실패. 셋 다 사진을 다시 찍어서 풀리는
            // 문제가 아니다. 사용자는 기다렸다 다시 시도해야 한다.
            log.warn(
                    "Receipt OCR call failed. failureType={}",
                    exception.getClass().getSimpleName()
            );
            throw ApplicationException.of(SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE, exception);
        }
    }

    /**
     * 상태 코드로 "서비스가 못 읽었다"와 "서비스에 닿지 못했다"를 가른다.
     *
     * @see #IMAGE_REJECTED_STATUSES
     */
    private ApplicationException translate(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        boolean unreachable = !IMAGE_REJECTED_STATUSES.contains(status.value());
        log.warn(
                "Receipt OCR rejected the request. status={} treatedAs={}",
                status.value(),
                unreachable ? "UNAVAILABLE" : "FAILED"
        );
        return ApplicationException.of(
                unreachable
                        ? SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE
                        : SettlementStatusCode.RECEIPT_OCR_FAILED,
                exception);
    }

    private MultiValueMap<String, Object> multipart(String fileName, String contentType, byte[] bytes) {
        ByteArrayResource image = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                // 파일 이름이 없으면 일부 서버가 파트를 파일로 보지 않는다.
                return fileName == null || fileName.isBlank() ? FALLBACK_FILE_NAME : fileName;
            }
        };

        // 파트의 Content-Type을 직접 박는다. 비워 두면 파일 이름의 확장자로 추측하게 되고,
        // 확장자가 없으면 application/octet-stream 으로 나가 서비스가 이미지로 보지 않는다.
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(imageMediaType(contentType));

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add(MULTIPART_FIELD, new HttpEntity<>(image, partHeaders));
        return form;
    }

    private MediaType imageMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.IMAGE_JPEG;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return new MediaType(parsed.getType(), parsed.getSubtype());
        } catch (InvalidMediaTypeException exception) {
            // 여기까지 오면 위층 검증이 이미 통과시킨 값이다. 호출은 막지 않는다.
            return MediaType.IMAGE_JPEG;
        }
    }
}
