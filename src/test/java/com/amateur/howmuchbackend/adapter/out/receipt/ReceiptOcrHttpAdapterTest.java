package com.amateur.howmuchbackend.adapter.out.receipt;

import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.infra.receipt.config.ReceiptOcrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("영수증 OCR HTTP 어댑터")
class ReceiptOcrHttpAdapterTest {
    private static final String BASE_URL = "http://ocr.internal:8600";
    private static final byte[] IMAGE = "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);

    private MockRestServiceServer server;
    private ReceiptOcrHttpAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new ReceiptOcrHttpAdapter(builder.build(), properties(BASE_URL));
    }

    private ReceiptOcrProperties properties(String baseUrl) {
        return new ReceiptOcrProperties(
                baseUrl, "/ocr/receipt", Duration.ofSeconds(3), Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("multipart file 필드로 보내고 응답을 결과로 옮긴다")
    void sendsImageAsMultipartAndMapsResponse() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                // 서비스가 받는 파트 이름은 file 이다. 이름이 어긋나면 422가 돌아온다.
                .andExpect(request -> assertThat(request.getBody().toString())
                        .contains("name=\"file\"")
                        .contains("receipt.jpg"))
                .andRespond(withSuccess("""
                        {
                          "ok": true,
                          "elapsed_sec": 3.1,
                          "result": {
                            "store_name": "스타필드",
                            "purchased_at": "2026-09-16 16:47",
                            "items": [
                              {"name": "참치김밥", "quantity": 1, "price": 3500},
                              {"name": "라면", "quantity": 2, "price": 7000}
                            ],
                            "total_amount": 10500,
                            "payment_method": "카드",
                            "total_verified": true
                          },
                          "corrections": [{"field":"item.name","before":"잠치김밥","after":"참치김밥","reason":"ocr"}],
                          "ocr_lines": [{"text":"스타필드","confidence":0.98}],
                          "raw": "모델 원본 응답"
                        }
                        """, MediaType.APPLICATION_JSON));

        ReceiptAnalysisResult result = adapter.analyze("receipt.jpg", "image/jpeg", IMAGE);

        assertThat(result.storeName()).isEqualTo("스타필드");
        assertThat(result.purchasedAt()).isEqualTo("2026-09-16 16:47");
        assertThat(result.totalAmount()).isEqualTo(10_500L);
        assertThat(result.paymentMethod()).isEqualTo("카드");
        assertThat(result.totalVerified()).isTrue();
        assertThat(result.items())
                .extracting(ReceiptAnalysisResult.Item::name, ReceiptAnalysisResult.Item::quantity,
                        ReceiptAnalysisResult.Item::price)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("참치김밥", 1, 3_500L),
                        org.assertj.core.groups.Tuple.tuple("라면", 2, 7_000L));
        server.verify();
    }

    @Test
    @DisplayName("파일 이름이 없어도 파트에 이름을 붙인다")
    void alwaysSendsAFileName() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt"))
                // 이름 없는 파트를 파일로 보지 않는 서버가 있다.
                .andExpect(request -> assertThat(request.getBody().toString()).contains("filename="))
                .andRespond(withSuccess(okBody(), MediaType.APPLICATION_JSON));

        adapter.analyze(null, "image/jpeg", IMAGE);

        server.verify();
    }

    @Test
    @DisplayName("읽지 못한 값은 null 그대로 전달한다")
    void keepsUnreadValuesAsNull() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt"))
                .andRespond(withSuccess("""
                        {"ok": true, "elapsed_sec": 2.0, "raw": "",
                         "result": {"items": [{"name": null, "quantity": null, "price": null}],
                                    "total_verified": false}}
                        """, MediaType.APPLICATION_JSON));

        ReceiptAnalysisResult result = adapter.analyze("a.jpg", "image/jpeg", IMAGE);

        assertThat(result.storeName()).isNull();
        assertThat(result.totalAmount()).isNull();
        assertThat(result.totalVerified()).isFalse();
        assertThat(result.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.name()).isNull();
                    assertThat(item.quantity()).isNull();
                    assertThat(item.price()).isNull();
                });
    }

    @Test
    @DisplayName("모르는 필드가 늘어도 깨지지 않는다")
    void toleratesUnknownFields() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt"))
                .andRespond(withSuccess("""
                        {"ok": true, "elapsed_sec": 1.0, "raw": "", "brand_new_field": {"x": 1},
                         "result": {"store_name": "가게", "total_verified": true, "unknown": 7}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.analyze("a.jpg", "image/jpeg", IMAGE).storeName()).isEqualTo("가게");
    }

    @Test
    @DisplayName("ok가 false면 이미지를 읽지 못한 것으로 본다")
    void treatsNotOkAsUnreadable() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt"))
                .andRespond(withSuccess(
                        "{\"ok\": false, \"error\": \"분석 실패\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyze("a.jpg", "image/jpeg", IMAGE))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.RECEIPT_OCR_FAILED);
    }

    @Test
    @DisplayName("ok가 true여도 result가 비면 읽지 못한 것으로 본다")
    void treatsMissingResultAsUnreadable() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt"))
                .andRespond(withSuccess(
                        "{\"ok\": true, \"elapsed_sec\": 1.0, \"raw\": \"\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.analyze("a.jpg", "image/jpeg", IMAGE))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.RECEIPT_OCR_FAILED);
    }

    @Test
    @DisplayName("서비스가 5xx로 거절하면 읽지 못한 것으로 본다")
    void treatsServerErrorAsUnreadable() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.analyze("a.jpg", "image/jpeg", IMAGE))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.RECEIPT_OCR_FAILED);
    }

    @Test
    @DisplayName("연결하지 못하면 잠시 후 다시 시도하도록 알린다")
    void treatsConnectionFailureAsUnavailable() {
        server.expect(requestTo(BASE_URL + "/ocr/receipt"))
                .andRespond(request -> {
                    throw new java.net.ConnectException("connection refused");
                });

        assertThatThrownBy(() -> adapter.analyze("a.jpg", "image/jpeg", IMAGE))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE);
    }

    @Test
    @DisplayName("주소가 비어 있으면 호출하지 않고 연결 불가로 답한다")
    void doesNotCallWhenIntegrationIsOff() {
        // 기대를 걸지 않은 서버를 붙인다. 요청이 한 건이라도 나가면 그 자리에서 실패한다.
        // 바깥의 server 를 그대로 쓰면 어댑터가 다른 RestClient 를 쓰므로 아무것도 증명하지 못한다.
        RestClient.Builder offBuilder = RestClient.builder();
        MockRestServiceServer offServer = MockRestServiceServer.bindTo(offBuilder).build();
        ReceiptOcrHttpAdapter off = new ReceiptOcrHttpAdapter(offBuilder.build(), properties(""));

        assertThatThrownBy(() -> off.analyze("a.jpg", "image/jpeg", IMAGE))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(SettlementStatusCode.RECEIPT_OCR_UNAVAILABLE);
        offServer.verify();
    }

    private String okBody() {
        return "{\"ok\": true, \"elapsed_sec\": 1.0, \"raw\": \"\", \"result\": {\"total_verified\": true}}";
    }
}
