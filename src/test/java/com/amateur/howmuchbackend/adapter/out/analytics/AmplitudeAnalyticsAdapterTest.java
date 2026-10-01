package com.amateur.howmuchbackend.adapter.out.analytics;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class AmplitudeAnalyticsAdapterTest {
    private MockRestServiceServer server;
    private AmplitudeAnalyticsAdapter adapter;
    private final LocalDate from = LocalDate.of(2026, 9, 29);
    private final LocalDate to = from.plusDays(1);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new AmplitudeAnalyticsAdapter(builder.build(), new ObjectMapper(), "test-api-key", "test-secret-key");
    }

    @Test
    void mapsRealResponseShapeAndOmitsDatesWithoutAmount() {
        expect("signup_completed", "totals", false, data("[2,0]", "[\"all\"]"));
        expect("settlement_request_sent", "totals", false, data("[0,3]", "[\"all\"]"));
        expect("settlement_closed", "totals", false, data("[0,0]", "[\"all\"]"));
        expect("settlement_request_sent", "totals", true,
                data("[0,2],[0,1]", "[[0,\"dutch\"],[0,\"itemized\"]]"));
        expect("settlement_request_sent", "value_avg", true,
                data("[0,1333.33]", "[0]"));
        server.expect(request -> {
                    assertThat(request.getURI().getRawQuery()).contains("m=totals", "set+is", "total_amount");
                })
                .andRespond(withSuccess(data("[0,3]", "[0]"), MediaType.APPLICATION_JSON));

        var snapshot = adapter.fetch(from, to);

        assertThat(snapshot.signups()).containsEntry(from, 2L);
        assertThat(snapshot.requests()).containsEntry(to, 3L);
        assertThat(snapshot.splitCounts()).containsEntry("dutch", 2L).containsEntry("itemized", 1L);
        assertThat(snapshot.averageAmounts()).containsEntry(to, new BigDecimal("1333.33"));
        assertThat(snapshot.averageAmounts()).doesNotContainKey(from);
        server.verify();
    }

    @Test
    void keepsRealZeroAmountWhenPropertyIsPresent() {
        expect("signup_completed", "totals", false, data("[0,0]", "[0]"));
        expect("settlement_request_sent", "totals", false, data("[1,1]", "[0]"));
        expect("settlement_closed", "totals", false, data("[0,0]", "[0]"));
        expect("settlement_request_sent", "totals", true, data("[1,1]", "[[0,\"dutch\"]]"));
        expect("settlement_request_sent", "value_avg", true, data("[0,0]", "[0]"));
        server.expect(request -> assertThat(request.getURI().getRawQuery()).contains("set+is"))
                .andRespond(withSuccess(data("[1,0]", "[0]"), MediaType.APPLICATION_JSON));

        var snapshot = adapter.fetch(from, to);

        assertThat(snapshot.averageAmounts()).containsEntry(from, BigDecimal.ZERO).doesNotContainKey(to);
        server.verify();
    }

    @Test
    void missingSecretFailsBeforeNetworkCall() {
        var unconfigured = new AmplitudeAnalyticsAdapter(RestClient.create(), new ObjectMapper(), "key", "");
        assertThatThrownBy(() -> unconfigured.fetch(from, to))
                .isInstanceOfSatisfying(ApplicationException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(CommonStatusCode.TEMPORARILY_UNAVAILABLE));
        server.verify();
    }

    @Test
    void upstreamFailureDoesNotExposeResponseBody() {
        server.expect(request -> assertThat(request.getURI().getPath())
                        .isEqualTo("/api/2/events/segmentation"))
                .andRespond(withServerError().body("private upstream details"));

        assertThatThrownBy(() -> adapter.fetch(from, to))
                .isInstanceOfSatisfying(ApplicationException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
                    assertThat(error.getMessage()).doesNotContain("private upstream details");
                });
        server.verify();
    }

    private void expect(String event, String metric, boolean grouped, String body) {
        server.expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/api/2/events/segmentation");
                    assertThat(request.getURI().getRawQuery()).contains(event).doesNotContain("ce%3A");
                    assertThat(request.getURI().getRawQuery()).contains("m=" + metric);
                    if (grouped) {
                        assertThat(request.getURI().getRawQuery()).contains("group_by");
                    }
                    assertThat(request.getHeaders().getFirst("Authorization")).startsWith("Basic ");
                })
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private String data(String series, String labels) {
        return "{\"data\":{\"xValues\":[\"2026-09-29\",\"2026-09-30\"],\"series\":[" + series
                + "],\"seriesLabels\":" + labels + "}}";
    }
}
