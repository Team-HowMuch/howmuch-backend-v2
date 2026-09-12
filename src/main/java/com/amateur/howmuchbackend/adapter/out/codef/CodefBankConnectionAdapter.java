package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CodefBankConnectionAdapter implements BankConnectionPort {
    private static final String SUCCESS = "CF-00000";
    private static final String ACCOUNT_CREATE_PATH = "/v1/account/create";
    private static final String ACCOUNT_ADD_PATH = "/v1/account/add";
    private static final String ACCOUNT_UPDATE_PATH = "/v1/account/update";
    private static final String ACCOUNT_LIST_PATH = "/v1/kr/bank/p/account/account-list";
    private static final BigDecimal BALANCE_STORAGE_LIMIT = new BigDecimal("1000000000000000");

    private final CodefClient client;
    private final CodefRsaEncryptor rsaEncryptor;
    private final CodefObservability observability;

    @Override
    public ConnectionResult create(BankRegistration registration) {
        JsonNode response = post(ACCOUNT_CREATE_PATH, "account_create", registration.organization(),
                Map.of("accountList", List.of(accountPayload(registration))));
        return connectionResult(response, registration.organization(), null);
    }

    @Override
    public ConnectionResult add(String connectedId, BankRegistration registration) {
        return change(ACCOUNT_ADD_PATH, connectedId, registration);
    }

    @Override
    public ConnectionResult update(String connectedId, BankRegistration registration) {
        return change(ACCOUNT_UPDATE_PATH, connectedId, registration);
    }

    private ConnectionResult change(String path, String connectedId, BankRegistration registration) {
        String api = ACCOUNT_ADD_PATH.equals(path) ? "account_add" : "account_update";
        JsonNode response = post(path, api, registration.organization(), Map.of(
                "connectedId", connectedId,
                "accountList", List.of(accountPayload(registration))
        ));
        return connectionResult(response, registration.organization(), connectedId);
    }

    @Override
    public List<ExternalAccountCandidate> findAccounts(
            String connectedId,
            String organization,
            String birthDate
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organization", organization);
        payload.put("connectedId", connectedId);
        putIfPresent(payload, "birthDate", birthDate);

        JsonNode data = data(post(ACCOUNT_LIST_PATH, "account_list", organization, payload));
        Map<String, ExternalAccountCandidate> accounts = new LinkedHashMap<>();
        addAccounts(accounts, data.path("resDepositTrust"), AccountType.DEPOSIT_TRUST);
        addAccounts(accounts, data.path("resForeignCurrency"), AccountType.FOREIGN_CURRENCY);
        return List.copyOf(accounts.values());
    }

    private Map<String, Object> accountPayload(BankRegistration registration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("countryCode", "KR");
        payload.put("businessType", "BK");
        payload.put("clientType", "P");
        payload.put("organization", registration.organization());
        payload.put("loginType", "1");
        payload.put("id", registration.loginId());
        payload.put("password", rsaEncryptor.encrypt(registration.password()));
        putIfPresent(payload, "birthDate", registration.birthDate());
        return payload;
    }

    private ConnectionResult connectionResult(JsonNode response, String organization, String fallbackConnectedId) {
        JsonNode data = data(response);
        boolean registered = values(data.path("successList")).stream()
                .anyMatch(item -> organization.equals(text(item, "organization")));
        String connectedId = firstText(text(data, "connectedId"), fallbackConnectedId);
        if (!registered || !StringUtils.hasText(connectedId)) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_CONNECTION_FAILED);
        }
        return new ConnectionResult(connectedId, text(response.path("result"), "code"));
    }

    private JsonNode post(String path, String api, String organization, Object payload) {
        long startedAt = observability.start();
        String resultCode = null;
        Throwable failure = null;
        try {
            JsonNode response = client.post(path, payload);
            CodefFailure codefFailure = failure(response);
            resultCode = codefFailure == null ? text(response.path("result"), "code") : codefFailure.code();
            ensureSuccess(response);
            return response;
        } catch (CodefClient.CodefHttpException exception) {
            CodefFailure codefFailure = failure(client.decode(exception.responseBody()));
            if (codefFailure != null) {
                resultCode = codefFailure.code();
                failure = mapped(resultCode, exception);
                throw (ApplicationException) failure;
            }
            ExternalConnectionStatusCode status = exception.status() >= 500
                    ? ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE
                    : ExternalConnectionStatusCode.BANK_CONNECTION_FAILED;
            failure = ApplicationException.of(status, exception);
            throw (ApplicationException) failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            observability.record(api, organization, resultCode, failure, startedAt);
        }
    }

    private void ensureSuccess(JsonNode response) {
        if (containsAdditionalAuth(response)) {
            throw ApplicationException.of(ExternalConnectionStatusCode.CODEF_ADDITIONAL_AUTH_REQUIRED);
        }
        CodefFailure failure = failure(response);
        if (failure != null) {
            throw mapped(failure.code(), null);
        }
        if (!SUCCESS.equals(text(response.path("result"), "code"))) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_CONNECTION_FAILED);
        }
    }

    private CodefFailure failure(JsonNode response) {
        for (JsonNode item : values(response.at("/data/errorList"))) {
            String code = text(item, "code");
            if (StringUtils.hasText(code) && !SUCCESS.equals(code)) {
                return new CodefFailure(code);
            }
        }
        String code = text(response.path("result"), "code");
        return StringUtils.hasText(code) && !SUCCESS.equals(code) ? new CodefFailure(code) : null;
    }

    private ApplicationException mapped(String code, Throwable cause) {
        return CodefFailureMapper.map(code, ExternalConnectionStatusCode.BANK_CONNECTION_FAILED, cause);
    }

    private void addAccounts(Map<String, ExternalAccountCandidate> target, JsonNode source, AccountType type) {
        for (JsonNode item : values(source)) {
            String externalKey = firstText(text(item, "resAccount"), text(item, "resAccountDisplay"));
            if (!StringUtils.hasText(externalKey)) {
                continue;
            }
            String display = firstText(text(item, "resAccountDisplay"), externalKey);
            target.putIfAbsent(externalKey, new ExternalAccountCandidate(
                    externalKey,
                    type,
                    firstText(text(item, "resAccountNickName"), text(item, "resAccountName"), "은행 계좌"),
                    mask(display),
                    amount(text(item, "resAccountBalance")),
                    text(item, "resAccountCurrency"),
                    text(item, "resAccountDeposit")
            ));
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

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private String mask(String value) {
        String digits = value.replaceAll("\\D", "");
        String normalized = digits.isEmpty() ? value.trim() : digits;
        return normalized.length() <= 4 ? normalized : "****" + normalized.substring(normalized.length() - 4);
    }

    private BigDecimal amount(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            BigDecimal amount = new BigDecimal(value.replace(",", "").trim());
            return amount.setScale(4, RoundingMode.HALF_UP).abs().compareTo(BALANCE_STORAGE_LIMIT) >= 0
                    ? null
                    : amount;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private record CodefFailure(String code) {
    }
}
