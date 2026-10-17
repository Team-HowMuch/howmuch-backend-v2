package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.application.certificate.model.CertificateProviderResult;
import com.amateur.howmuchbackend.application.external.model.*;
import com.amateur.howmuchbackend.application.port.out.external.CodefAdditionalAuthenticationPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.AdditionalAuthenticationStatusCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        prefix = "howmuch.codef.additional-auth",
        name = "enabled",
        havingValue = "true")
public class CodefSmsContinuationAdapter implements CodefAdditionalAuthenticationPort {
    private final CodefClient client;
    private final ObjectMapper mapper;
    private final com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties
            properties;
    private static final Set<String> PATHS =
            Set.of(
                    "/v1/account/create",
                    "/v1/account/add",
                    "/v1/account/update",
                    "/v1/kr/bank/p/account/account-list",
                    "/v1/kr/bank/p/account/transaction-list");
    private static final String SMS_INPUT_FIELD = "smsAuthNo";
    // Keep existing encrypted contexts readable; reqSMSAuthNo is output phone metadata.
    private static final Set<String> STORED_SMS_FIELDS = Set.of("reqSMSAuthNo", SMS_INPUT_FIELD);

    public void requireIfSupported(
            String path, Object request, JsonNode response, String organization) {
        if (!PATHS.contains(path)) return;
        var nodes = new ArrayList<JsonNode>();
        continuations(response, nodes);
        if (nodes.size() != 1) return;
        var n = nodes.getFirst();
        // A CF code, SMS-like message, or other authentication method is never enough.
        if (!Set.of("sms", "smsauth", "smsauthno")
                        .contains(n.path("method").asText("").toLowerCase(Locale.ROOT))
                || !n.path("jobIndex").isIntegralNumber()
                || !n.path("threadIndex").isIntegralNumber()
                || n.path("jti").asText("").isBlank()
                || !n.path("twoWayTimestamp").isIntegralNumber()) return;
        ObjectNode context = mapper.createObjectNode();
        context.put("path", path);
        context.put("organization", organization);
        context.put("field", SMS_INPUT_FIELD);
        context.set("request", mapper.valueToTree(request));
        ObjectNode twoWay = mapper.createObjectNode();
        for (String f : List.of("jobIndex", "threadIndex", "jti", "twoWayTimestamp"))
            twoWay.set(f, n.path(f));
        context.set("twoWayInfo", twoWay);
        int timeout = n.path("reqTimeOut").asInt(170);
        if (timeout < 1 || timeout > 600) timeout = 170;
        String destination =
                n.path("extraInfo")
                        .path("reqSMSAuthNo")
                        .asText(n.path("extraInfo").path("smsAuthNo").asText(""));
        // Never disclose an unmasked provider value or free-form text.
        if (!destination.matches("[0-9*+ -]{4,64}") || !destination.contains("*"))
            destination = null;
        throw new AdditionalAuthenticationRequiredException(
                new AdditionalAuthenticationRequest(
                        mapper.writeValueAsBytes(context),
                        destination,
                        timeout,
                        java.time.LocalDateTime.ofInstant(
                                java.time.Instant.ofEpochMilli(n.path("twoWayTimestamp").asLong())
                                        .plusSeconds(timeout),
                                java.time.ZoneOffset.UTC)));
    }

    public byte[] acceptSms(byte[] context, String code) {
        if (code == null || !code.matches("[0-9]{4,8}"))
            throw ApplicationException.of(
                    AdditionalAuthenticationStatusCode.FINANCE_AUTHENTICATION_INPUT_INVALID);
        ObjectNode c = validated(context);
        c.put("code", code);
        return mapper.writeValueAsBytes(c);
    }

    public CertificateProviderResult resume(byte[] accepted) {
        ObjectNode c = validated(accepted);
        String code = c.path("code").asText("");
        if (!code.matches("[0-9]{4,8}")) throw new IllegalArgumentException("Invalid SMS context");
        ObjectNode body = smsBody(c, code);
        JsonNode response = client.postOnce(c.path("path").asText(), body);
        requireRetry(c, response);
        return result(
                response, c.path("organization").asText(), body.path("connectedId").asText(""));
    }

    public byte[] prepareOperation(byte[] context, FinanceOperation operation) {
        ObjectNode c = validated(context);
        c.set("operation", mapper.valueToTree(operation));
        return mapper.writeValueAsBytes(c);
    }

    public byte[] encodeCompletion(FinanceAuthenticationCompletion result) {
        return mapper.writeValueAsBytes(result);
    }

    public FinanceAuthenticationCompletion decodeCompletion(byte[] bytes) {
        return mapper.readValue(bytes, FinanceAuthenticationCompletion.class);
    }

    public FinanceOperationResult resumeOperation(byte[] accepted) {
        ObjectNode c = validated(accepted);
        FinanceOperation operation =
                mapper.treeToValue(c.path("operation"), FinanceOperation.class);
        String code = c.path("code").asText("");
        if (!code.matches("[0-9]{4,8}")) throw new IllegalArgumentException("Invalid SMS context");
        ObjectNode body = smsBody(c, code);
        String path = c.path("path").asText();
        String organization = c.path("organization").asText();
        log.info(
                "Sending CODEF SMS continuation. api={} organization={} field={} placement={}"
                        + " codeLength={}",
                apiName(path),
                safeOrganization(organization),
                SMS_INPUT_FIELD,
                "root",
                code.length());

        JsonNode response;
        try {
            response = client.postOnce(path, body);
        } catch (CodefClient.CodefHttpException failure) {
            JsonNode providerError = safeDecode(failure.responseBody());
            log.warn(
                    "CODEF SMS continuation HTTP failure. api={} organization={} httpStatus={}"
                        + " resultCode={} itemCode={}",
                    apiName(path),
                    safeOrganization(organization),
                    failure.status(),
                    providerCode(providerError, "/result/code"),
                    providerCode(providerError, "/data/errorList/0/code"));
            throw failure;
        } catch (RuntimeException failure) {
            log.warn(
                    "CODEF SMS continuation transport failure. api={} organization={}"
                        + " failureType={}",
                    apiName(path),
                    safeOrganization(organization),
                    failure.getClass().getSimpleName());
            throw failure;
        }

        requireRetry(c, response);
        String resultCode = providerCode(response, "/result/code");
        String itemCode = providerCode(response, "/data/errorList/0/code");
        boolean itemFailure = !"unknown".equals(itemCode) && !"CF-00000".equals(itemCode);
        if (!"CF-00000".equals(resultCode) || itemFailure) {
            log.warn(
                    "CODEF SMS continuation response. api={} organization={} resultCode={}"
                        + " itemCode={}",
                    apiName(path),
                    safeOrganization(organization),
                    resultCode,
                    itemCode);
        } else {
            log.info(
                    "CODEF SMS continuation response. api={} organization={} resultCode={}"
                        + " itemCode={}",
                    apiName(path),
                    safeOrganization(organization),
                    resultCode,
                    itemCode);
        }
        var further = new ArrayList<JsonNode>();
        continuations(response, further);
        if (!further.isEmpty())
            throw new AdditionalAuthenticationRejectedException(
                    com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode
                            .CODEF_ADDITIONAL_AUTH_REQUIRED);
        String failure =
                response.at("/data/errorList/0/code")
                        .asText(response.at("/result/code").asText(""));
        if (!failure.matches("CF-[0-9]{5}"))
            throw ApplicationException.of(
                    com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode
                            .CODEF_RESPONSE_INVALID);
        if (!"CF-00000".equals(failure))
            throw new AdditionalAuthenticationRejectedException(CodefFailureMapper.status(
                    failure,
                    com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode
                            .CODEF_UNMAPPED_ERROR));
        return switch (operation.type()) {
            case "BANK_CONNECT" ->
                    new FinanceOperationResult(
                            operation,
                            CodefBankConnectionAdapter.connectionResult(
                                    response,
                                    operation.organization(),
                                    body.path("connectedId").asText(null)),
                            null,
                            null);
            case "ACCOUNT_LIST" ->
                    new FinanceOperationResult(
                            operation,
                            null,
                            CodefBankConnectionAdapter.parseAccounts(response),
                            null);
            case "TRANSACTION_LIST" ->
                    new FinanceOperationResult(
                            operation,
                            null,
                            null,
                            CodefBankTransactionAdapter.parseTransactions(response));
            default -> throw new IllegalArgumentException("Unsupported operation");
        };
    }

    private void requireRetry(ObjectNode context, JsonNode response) {
        if (!response.at("/data/successList").isEmpty()) return;
        try {
            requireIfSupported(
                    context.path("path").asText(),
                    context.path("request"),
                    response,
                    context.path("organization").asText());
        } catch (AdditionalAuthenticationRequiredException required) {
            var request = required.request();
            byte[] raw = request.payload();
            try {
                ObjectNode retry = (ObjectNode) mapper.readTree(raw);
                if (context.has("operation")) retry.set("operation", context.path("operation"));
                throw new AdditionalAuthenticationRequiredException(
                        new AdditionalAuthenticationRequest(
                                mapper.writeValueAsBytes(retry), request.maskedDestination(),
                                request.timeoutSeconds(), request.providerExpiresAt()));
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        }
    }

    private ObjectNode smsBody(ObjectNode context, String code) {
        ObjectNode body = (ObjectNode) context.path("request").deepCopy();
        body.put("is2Way", true);
        body.set("twoWayInfo", context.path("twoWayInfo").deepCopy());
        // SMS input is top-level; extraInfo.reqSMSAuthNo is the receiving phone number.
        body.put(SMS_INPUT_FIELD, code);
        return body;
    }

    private JsonNode safeDecode(String body) {
        try {
            return client.decode(body);
        } catch (RuntimeException ignored) {
            return mapper.createObjectNode();
        }
    }

    private static String providerCode(JsonNode response, String pointer) {
        String code = response.at(pointer).asText("");
        return code.matches("CF-[0-9]{5}") ? code : "unknown";
    }

    private static String safeOrganization(String organization) {
        return organization != null && organization.matches("[0-9]{4}") ? organization : "unknown";
    }

    private static String apiName(String path) {
        return switch (path) {
            case "/v1/account/create" -> "account_create";
            case "/v1/account/add" -> "account_add";
            case "/v1/account/update" -> "account_update";
            case "/v1/kr/bank/p/account/account-list" -> "account_list";
            case "/v1/kr/bank/p/account/transaction-list" -> "transaction_list";
            default -> "unknown";
        };
    }

    private ObjectNode validated(byte[] bytes) {
        JsonNode c = mapper.readTree(bytes);
        if (!c.isObject()
                || !PATHS.contains(c.path("path").asText())
                || !STORED_SMS_FIELDS.contains(c.path("field").asText())
                || !c.path("request").isObject()
                || (c.path("path").asText().startsWith("/v1/account/")
                        && (c.at("/request/accountList").size() != 1
                                || !c.at("/request/accountList/0").isObject()))
                || !c.path("twoWayInfo").isObject())
            throw new IllegalArgumentException("Invalid continuation context");
        return (ObjectNode) c;
    }

    private CertificateProviderResult result(JsonNode r, String organization, String knownId) {
        var further = new ArrayList<JsonNode>();
        continuations(r, further);
        if (!further.isEmpty())
            return CertificateProviderResult.rejected("ADDITIONAL_AUTH_REQUIRED");
        String outer = r.at("/result/code").asText("");
        String returned = r.at("/data/connectedId").asText(knownId);
        if (!knownId.isBlank() && !returned.equals(knownId))
            throw new CodefCertificateRegistrationAdapter.CertificateProviderUncertainException();
        boolean success = false;
        for (JsonNode n : r.at("/data/successList"))
            if (organization.equals(n.path("organization").asText())
                    && "CF-00000".equals(n.path("code").asText("CF-00000"))) success = true;
        if ("CF-00000".equals(outer)
                && success
                && r.at("/data/errorList").isEmpty()
                && !returned.isBlank()) return CertificateProviderResult.connected(returned);
        if (success || (!knownId.equals(returned) && !returned.isBlank()))
            throw new CodefCertificateRegistrationAdapter.CertificateProviderUncertainException();
        String failure = r.at("/data/errorList/0/code").asText(outer);
        if (!failure.matches("CF-[0-9]{5}")
                || Set.of("CF-00000", "CF-00016", "CF-04012").contains(failure))
            throw new CodefCertificateRegistrationAdapter.CertificateProviderUncertainException();
        return CertificateProviderResult.rejected(
                properties.detailedErrorsEnabled() ? failure : "REGISTRATION_REJECTED");
    }

    private void continuations(JsonNode n, List<JsonNode> found) {
        if (n.isObject() && n.path("continue2Way").asBoolean(false)) {
            found.add(n);
            return;
        }
        for (JsonNode child : n) continuations(child, found);
    }
}
