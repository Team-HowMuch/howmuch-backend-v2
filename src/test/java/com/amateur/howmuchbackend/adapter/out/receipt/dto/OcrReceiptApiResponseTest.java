package com.amateur.howmuchbackend.adapter.out.receipt.dto;

import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OCR 서비스 응답 매핑")
class OcrReceiptApiResponseTest {
    /**
     * 모르는 필드에 예외를 발생시키는 매퍼를 사용한다.
     *
     * <p>Jackson 3은 FAIL_ON_UNKNOWN_PROPERTIES를 기본값으로 false로 설정한다. 기본 매퍼로 검증하면
     * {@code @JsonIgnoreProperties} 를 통째로 지워도 테스트가 통과한다. 여기서는 켜 두고,
     * 애노테이션이 실제로 일하는지를 본다.
     */
    private final ObjectMapper strict = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Test
    @DisplayName("디버깅용 필드가 섞여 와도 매핑이 깨지지 않는다")
    void receiptOcr_debugAndUnknownFields_ignored() {
        String body = """
                {"ok": true, "elapsed_sec": 3.1, "raw": "모델 원본",
                 "ocr_lines": [{"text": "스타필드", "confidence": 0.98}],
                 "corrections": [{"field": "item.name", "before": "잠치", "after": "참치"}],
                 "brand_new_field": {"x": 1},
                 "result": {"store_name": "스타필드", "total_verified": true, "unknown": 7,
                            "items": [{"name": "김밥", "quantity": 1, "price": 3500, "code": "A"}]}}
                """;

        OcrReceiptApiResponse response = strict.readValue(body, OcrReceiptApiResponse.class);

        assertThat(response.hasResult()).isTrue();
        assertThat(response.result().storeName()).isEqualTo("스타필드");
    }

    @Test
    @DisplayName("snake_case 필드를 우리 이름으로 옮긴다")
    void receiptOcr_snakeCaseFields_mapped() {
        String body = """
                {"ok": true, "result": {"store_name": "가게", "purchased_at": "2026-09-16 16:47",
                 "total_amount": 10500, "payment_method": "카드", "total_verified": true,
                 "items": [{"name": "라면", "quantity": 2, "price": 7000}]}}
                """;

        ReceiptAnalysisResult result =
                strict.readValue(body, OcrReceiptApiResponse.class).toResult();

        assertThat(result.storeName()).isEqualTo("가게");
        assertThat(result.purchasedAt()).isEqualTo("2026-09-16 16:47");
        assertThat(result.totalAmount()).isEqualTo(10_500L);
        assertThat(result.paymentMethod()).isEqualTo("카드");
        assertThat(result.totalVerified()).isTrue();
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("라면");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.price()).isEqualTo(7_000L);
        });
    }

    @Test
    @DisplayName("OCR 품목 옵션과 품목·전체 할인을 그대로 옮긴다")
    void receiptOcr_optionsAndDiscounts_mapped() {
        String body = """
                {"ok": true, "result": {"discount": 1000, "total_amount": 12000,
                 "total_verified": true,
                 "items": [{"name": "커피", "quantity": 2, "price": 6500,
                            "discount": 500,
                            "sub_items": [{"name": "샷 추가", "price": 500}]}]}}
                """;

        ReceiptAnalysisResult result =
                strict.readValue(body, OcrReceiptApiResponse.class).toResult();

        assertThat(result.discount()).isEqualTo(1_000);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.discount()).isEqualTo(500);
            assertThat(item.subItems()).singleElement().satisfies(option -> {
                assertThat(option.name()).isEqualTo("샷 추가");
                assertThat(option.price()).isEqualTo(500);
            });
        });
    }

    @Test
    @DisplayName("배달비를 옮기고, 0과 값 없음을 구분한다")
    void receiptOcr_deliveryFee_mapped() {
        String withFee = """
                {"ok": true, "result": {"total_amount": 12000, "total_verified": true,
                 "discount": 0, "delivery_fee": 3000,
                 "items": [{"name": "짬뽕", "quantity": 1, "price": 9000}]}}
                """;
        String zeroFee = "{\"ok\": true, \"result\": {\"total_verified\": true, \"delivery_fee\": 0}}";
        String noFee = "{\"ok\": true, \"result\": {\"total_verified\": true}}";

        assertThat(strict.readValue(withFee, OcrReceiptApiResponse.class).toResult().deliveryFee())
                .isEqualTo(3_000L);
        // 0은 "배달비 없음(또는 못 찾음)", null은 "분석 서비스가 값을 주지 않음"이라 섞이면 안 된다.
        assertThat(strict.readValue(zeroFee, OcrReceiptApiResponse.class).toResult().deliveryFee())
                .isZero();
        assertThat(strict.readValue(noFee, OcrReceiptApiResponse.class).toResult().deliveryFee())
                .isNull();
    }

    @Test
    @DisplayName("항목 배열에 null 원소가 섞여도 변환에서 터지지 않는다")
    void receiptOcr_nullItems_dropped() {
        String body = """
                {"ok": true, "result": {"store_name": "가게", "total_verified": false,
                 "items": [null, {"name": "김밥", "quantity": 1, "price": 3500}]}}
                """;

        ReceiptAnalysisResult result =
                strict.readValue(body, OcrReceiptApiResponse.class).toResult();

        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.name()).isEqualTo("김밥"));
    }

    @Test
    @DisplayName("items가 없으면 빈 목록이 된다")
    void receiptOcr_missingItems_returnsEmptyList() {
        String body = "{\"ok\": true, \"result\": {\"store_name\": \"가게\", \"total_verified\": false}}";

        assertThat(strict.readValue(body, OcrReceiptApiResponse.class).toResult().items()).isEmpty();
    }

    @Test
    @DisplayName("ok가 true여도 result가 없으면 결과 없음이다")
    void receiptOcr_missingResult_returnsNoResult() {
        String body = "{\"ok\": true, \"elapsed_sec\": 1.0}";

        assertThat(strict.readValue(body, OcrReceiptApiResponse.class).hasResult()).isFalse();
    }

    @Test
    @DisplayName("실패 응답은 결과 없음이다")
    void receiptOcr_notOkResponse_returnsNoResult() {
        String body = "{\"ok\": false, \"error\": \"분석 실패\"}";

        assertThat(strict.readValue(body, OcrReceiptApiResponse.class).hasResult()).isFalse();
    }
}
