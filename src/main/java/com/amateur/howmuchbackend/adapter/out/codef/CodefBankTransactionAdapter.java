package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CodefBankTransactionAdapter implements BankTransactionPort {
    private static final String SUCCESS = "CF-00000";
    private static final String TRANSACTION_LIST_PATH = "/v1/kr/bank/p/account/transaction-list";
    private static final DateTimeFormatter CODEF_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter CODEF_TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CodefClient client;
    private final CodefObservability observability;

    @Override
    public List<BankTransaction> fetch(
            String connectedId,
            String organization,
            String account,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("connectedId", connectedId);
        payload.put("organization", organization);
        payload.put("account", account);
        payload.put("startDate", CODEF_DATE.format(startDate));
        payload.put("endDate", CODEF_DATE.format(endDate));
        payload.put("orderBy", "1");
        payload.put("inquiryType", "1");

        JsonNode response = post(organization, payload);
        List<BankTransaction> transactions = new ArrayList<>();
        for (JsonNode item : values(data(response).path("resTrHistoryList"))) {
            BankTransaction transaction = transaction(item);
            if (transaction != null) {
                transactions.add(transaction);
            }
        }
        return List.copyOf(transactions);
    }

    private JsonNode post(String organization, Map<String, Object> payload) {
        long startedAt = observability.start();
        String resultCode = null;
        Throwable failure = null;
        try {
            JsonNode response = client.post(TRANSACTION_LIST_PATH, payload);
            resultCode = text(response.path("result"), "code");
            ensureSuccess(response);
            return response;
        } catch (CodefClient.CodefHttpException exception) {
            resultCode = text(client.decode(exception.responseBody()).path("result"), "code");
            failure = mapped(resultCode, exception);
            throw (ApplicationException) failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            observability.record("transaction_list", organization, resultCode, failure, startedAt);
        }
    }

    private void ensureSuccess(JsonNode response) {
        if (containsAdditionalAuth(response)) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED);
        }
        String code = text(response.path("result"), "code");
        if (!SUCCESS.equals(code)) {
            throw mapped(code, null);
        }
    }

    private ApplicationException mapped(String code, Throwable cause) {
        return CodefFailureMapper.map(code, ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE, cause);
    }

    private BankTransaction transaction(JsonNode item) {
        LocalDateTime transactedAt = transactedAt(item);
        BigDecimal out = positiveAmount(text(item, "resAccountOut"));
        BigDecimal in = positiveAmount(text(item, "resAccountIn"));
        AccountTransactionType type = out != null ? AccountTransactionType.EXPENSE
                : in != null ? AccountTransactionType.INCOME : null;
        BigDecimal transactionAmount = out != null ? out : in;
        if (transactedAt == null || type == null || transactionAmount.signum() <= 0) {
            return null;
        }
        return new BankTransaction(
                type,
                transactionAmount,
                transactedAt,
                description(item),
                signedAmount(text(item, "resAfterTranBalance"))
        );
    }

    private LocalDateTime transactedAt(JsonNode item) {
        String date = digits(text(item, "resAccountTrDate"));
        String time = digits(text(item, "resAccountTrTime"));
        if (date == null || date.length() != 8 || time == null || time.length() != 6) {
            return null;
        }
        try {
            return LocalDateTime.of(LocalDate.parse(date, CODEF_DATE), LocalTime.parse(time, CODEF_TIME))
                    .atZone(SEOUL)
                    .withZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private String description(JsonNode item) {
        List<String> values = new ArrayList<>();
        for (String field : List.of(
                "resAccountDesc1", "resAccountDesc2", "resAccountDesc3", "resAccountDesc4"
        )) {
            String value = text(item, field);
            if (value != null && !values.contains(value)) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            String fallback = text(item, "resAccountDesc");
            if (fallback != null) {
                values.add(fallback);
            }
        }
        String result = String.join(" ", values);
        return result.length() <= 500 ? result : result.substring(0, 500);
    }

    private BigDecimal positiveAmount(String value) {
        BigDecimal amount = signedAmount(value);
        return amount != null && amount.signum() > 0 ? amount : null;
    }

    private BigDecimal signedAmount(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean containsAdditionalAuth(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isObject() && node.path("continue2Way").asBoolean(false)) {
            return true;
        }
        for (JsonNode child : node) {
            if (containsAdditionalAuth(child)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode data(JsonNode response) {
        JsonNode data = response.path("data");
        return data.isMissingNode() || data.isNull() ? response : data;
    }

    private List<JsonNode> values(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            node.forEach(values::add);
            return values;
        }
        return node.isObject() ? List.of(node) : List.of();
    }

    private String text(JsonNode node, String field) {
        String value = node == null ? null : node.path(field).asText(null);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String digits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }
}
