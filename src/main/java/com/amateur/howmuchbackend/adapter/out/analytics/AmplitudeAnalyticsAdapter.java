package com.amateur.howmuchbackend.adapter.out.analytics;

import com.amateur.howmuchbackend.application.port.out.analytics.AnalyticsSnapshot;
import com.amateur.howmuchbackend.application.port.out.analytics.FetchAnalyticsPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Component
public class AmplitudeAnalyticsAdapter implements FetchAnalyticsPort {
    private static final String ENDPOINT = "https://amplitude.com/api/2/events/segmentation";
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int GROUP_LIMIT = 1000;

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String secretKey;

    public AmplitudeAnalyticsAdapter(
            @Qualifier("amplitudeRestClient") RestClient client,
            ObjectMapper mapper,
            @Value("${howmuch.analytics.amplitude.api-key:}") String apiKey,
            @Value("${howmuch.analytics.amplitude.secret-key:}") String secretKey
    ) {
        this.client = client;
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
    }

    @Override
    public AnalyticsSnapshot fetch(LocalDate from, LocalDate to) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            throw unavailable();
        }
        return new AnalyticsSnapshot(
                counts(query("signup_completed", null, from, to), from, to),
                counts(query("settlement_request_sent", null, from, to), from, to),
                counts(query("settlement_closed", null, from, to), from, to),
                splitCounts(query("settlement_request_sent", "split_type", from, to), from, to),
                averageAmounts(query("settlement_request_sent", "total_amount", "value_avg", from, to), from, to)
        );
    }

    private JsonNode query(String event, String groupBy, LocalDate from, LocalDate to) {
        return query(event, groupBy, "totals", from, to);
    }

    private JsonNode query(String event, String groupBy, String metric, LocalDate from, LocalDate to) {
        try {
            String eventJson = groupBy == null
                    ? "{\"event_type\":\"ce:%s\"}".formatted(event)
                    : "{\"event_type\":\"ce:%s\",\"group_by\":[{\"type\":\"event\",\"value\":\"%s\"}]}"
                            .formatted(event, groupBy);
            String uri = ENDPOINT + "?e=" + URLEncoder.encode(eventJson, StandardCharsets.UTF_8)
                    + "&start=" + API_DATE.format(from) + "&end=" + API_DATE.format(to)
                    + "&i=1&m=" + metric + (groupBy == null ? "" : "&limit=" + GROUP_LIMIT);
            String body = client.get().uri(URI.create(uri))
                    .headers(headers -> headers.setBasicAuth(apiKey, secretKey))
                    .retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw unavailable();
            }
            JsonNode data = mapper.readTree(body).path("data");
            if (!data.isObject() || !data.path("xValues").isArray() || !data.path("series").isArray()) {
                throw unavailable();
            }
            return data;
        } catch (RestClientException | JacksonException | IllegalArgumentException exception) {
            // Amplitude 응답 본문과 인증 정보는 API 오류 또는 로그에 싣지 않는다.
            throw unavailable();
        }
    }

    private Map<LocalDate, Long> counts(JsonNode data, LocalDate from, LocalDate to) {
        JsonNode series = data.path("series");
        if (series.size() > 1) {
            throw unavailable();
        }
        return series.isEmpty() ? Map.of() : dailyCounts(data, series.get(0), from, to);
    }

    private Map<String, Long> splitCounts(JsonNode data, LocalDate from, LocalDate to) {
        JsonNode series = data.path("series");
        JsonNode labels = data.path("seriesLabels");
        requireGroups(series, labels);
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < series.size(); i++) {
            String label = label(labels.get(i));
            long total = dailyCounts(data, series.get(i), from, to).values().stream()
                    .mapToLong(Long::longValue).sum();
            result.merge(label, total, Math::addExact);
        }
        return Map.copyOf(result);
    }

    private Map<LocalDate, BigDecimal> averageAmounts(JsonNode data, LocalDate from, LocalDate to) {
        JsonNode series = data.path("series");
        if (series.size() > 1) {
            throw unavailable();
        }
        if (series.isEmpty()) {
            return Map.of();
        }
        JsonNode values = series.get(0);
        JsonNode dates = data.path("xValues");
        if (!values.isArray() || values.size() != dates.size()) {
            throw unavailable();
        }
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        try {
            for (int i = 0; i < dates.size(); i++) {
                LocalDate date = LocalDate.parse(label(dates.get(i)));
                JsonNode value = values.get(i);
                if (date.isBefore(from) || date.isAfter(to) || result.containsKey(date)) {
                    throw unavailable();
                }
                if (value.isNull()) {
                    continue;
                }
                if (!value.isNumber()) {
                    throw unavailable();
                }
                BigDecimal amount = new BigDecimal(value.toString());
                if (amount.signum() < 0) {
                    throw unavailable();
                }
                result.put(date, amount);
            }
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        return Map.copyOf(result);
    }

    private Map<LocalDate, Long> dailyCounts(JsonNode data, JsonNode values, LocalDate from, LocalDate to) {
        JsonNode dates = data.path("xValues");
        if (!values.isArray() || values.size() != dates.size()) {
            throw unavailable();
        }
        Map<LocalDate, Long> result = new HashMap<>();
        try {
            for (int i = 0; i < dates.size(); i++) {
                JsonNode value = values.get(i);
                LocalDate date = LocalDate.parse(label(dates.get(i)));
                if (date.isBefore(from) || date.isAfter(to) || result.containsKey(date)
                        || !value.isIntegralNumber()) {
                    throw unavailable();
                }
                long count = new BigDecimal(value.toString()).longValueExact();
                if (count < 0) {
                    throw unavailable();
                }
                result.put(date, count);
            }
        } catch (RuntimeException exception) {
            throw unavailable();
        }
        return Map.copyOf(result);
    }

    private void requireGroups(JsonNode series, JsonNode labels) {
        if (!labels.isArray() || labels.size() != series.size() || series.size() >= GROUP_LIMIT) {
            throw unavailable();
        }
    }

    private String label(JsonNode value) {
        if (value == null || !value.isString()) {
            throw unavailable();
        }
        return value.stringValue();
    }

    private ApplicationException unavailable() {
        return ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
    }
}
