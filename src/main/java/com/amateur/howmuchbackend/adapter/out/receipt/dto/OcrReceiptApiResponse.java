package com.amateur.howmuchbackend.adapter.out.receipt.dto;

import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * 내부 OCR 서비스의 응답.
 *
 * <p>디버깅용 필드(raw, ocr_lines, corrections)는 일부러 매핑하지 않는다. 읽어 두면 로그나
 * 응답에 새어 나갈 길이 생기고, 우리가 쓰지도 않는다.
 *
 * <p>모르는 필드는 무시한다. OCR 서비스가 필드를 추가해도 백엔드가 깨지지 않아야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OcrReceiptApiResponse(
        boolean ok,
        Result result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @JsonProperty("store_name") String storeName,
            @JsonProperty("purchased_at") String purchasedAt,
            List<Item> items,
            @JsonProperty("total_amount") Long totalAmount,
            @JsonProperty("payment_method") String paymentMethod,
            @JsonProperty("total_verified") boolean totalVerified
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String name,
            Integer quantity,
            Long price
    ) {
    }

    /**
     * 분석에 성공했고 결과가 있는지.
     *
     * <p>{@code ok}가 true여도 {@code result}가 null일 수 있다. 서비스 스키마에서 선택 항목이다.
     */
    public boolean hasResult() {
        return ok && result != null;
    }

    public ReceiptAnalysisResult toResult() {
        return new ReceiptAnalysisResult(
                result.storeName(),
                result.purchasedAt(),
                result.items() == null
                        ? List.of()
                        // 배열 안에 null 원소가 섞여 올 수 있다. 서비스 스키마가 막지 않는다.
                        : result.items().stream()
                                .filter(Objects::nonNull)
                                .map(item -> new ReceiptAnalysisResult.Item(
                                        item.name(), item.quantity(), item.price()))
                                .toList(),
                result.totalAmount(),
                result.paymentMethod(),
                result.totalVerified()
        );
    }
}
