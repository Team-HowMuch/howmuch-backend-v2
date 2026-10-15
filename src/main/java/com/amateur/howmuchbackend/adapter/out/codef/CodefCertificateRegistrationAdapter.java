package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateProviderAccountPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

import java.util.*;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CodefCertificateRegistrationAdapter implements CertificateProviderAccountPort {
    private final CodefClient client;
    private final CodefRsaEncryptor rsa;
    private final boolean detailedErrorsEnabled;

    public CodefCertificateRegistrationAdapter(
            CodefClient client, CodefRsaEncryptor rsa, CertificateProperties properties) {
        this.client = client;
        this.rsa = rsa;
        this.detailedErrorsEnabled = properties.detailedErrorsEnabled();
    }

    public CertificateProviderResult register(
            String organization, CertificateMaterial material, boolean includeAccountCredentials) {
        return change(
                "/v1/account/create", null, organization, material, includeAccountCredentials);
    }

    public CertificateProviderResult add(
            String connectedId,
            String organization,
            CertificateMaterial material,
            boolean includeAccountCredentials) {
        return change(
                "/v1/account/add", connectedId, organization, material, includeAccountCredentials);
    }

    public CertificateProviderResult update(
            String connectedId,
            String organization,
            CertificateMaterial material,
            boolean includeAccountCredentials) {
        return change(
                "/v1/account/update",
                connectedId,
                organization,
                material,
                includeAccountCredentials);
    }

    private CertificateProviderResult change(
            String path,
            String knownId,
            String organization,
            CertificateMaterial material,
            boolean includeAccountCredentials) {
        try {
            return registerOnce(path, knownId, organization, material, includeAccountCredentials);
        } catch (RuntimeException failure) {
            logFailure(organization, failure);
            throw new CertificateProviderUncertainException();
        }
    }

    private CertificateProviderResult registerOnce(
            String path,
            String knownId,
            String organization,
            CertificateMaterial material,
            boolean includeAccountCredentials) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("countryCode", "KR");
        account.put("businessType", "BK");
        account.put("clientType", "P");
        account.put("organization", organization);
        account.put("loginType", "0");
        account.put("certType", "1");
        byte[] der = material.der(), key = material.key();
        int derBytes = der.length, keyBytes = key.length;
        boolean derAllZero = allZero(der), keyAllZero = allZero(key);
        try {
            account.put("derFile", Base64.getEncoder().encodeToString(der));
            account.put("keyFile", Base64.getEncoder().encodeToString(key));
        } finally {
            Arrays.fill(der, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
        account.put("password", rsa.encrypt(material.password()));
        if (includeAccountCredentials && material.accountNumber() != null) {
            account.put("withdrawAccountNo", material.accountNumber());
            account.put("withdrawAccountPassword", rsa.encrypt(material.accountPassword()));
        }
        if (material.birthDate() != null) account.put("birthDate", material.birthDate());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountList", List.of(account));
        if (knownId != null) payload.put("connectedId", knownId);
        JsonNode response = client.postOnce(path, payload);
        String returnedId = response.at("/data/connectedId").asText("");
        if (knownId != null && !returnedId.isBlank() && !knownId.equals(returnedId))
            throw new CertificateProviderUncertainException();
        String connectedId = returnedId.isBlank() && knownId != null ? knownId : returnedId;
        var success = response.at("/data/successList");
        boolean registered = false;
        for (JsonNode item : success) {
            if (organization.equals(item.path("organization").asText())
                    && "CF-00000".equals(item.path("code").asText("CF-00000"))) registered = true;
        }
        boolean errors = response.at("/data/errorList").size() > 0;
        if (!"CF-00000".equals(response.at("/result/code").asText())
                || !registered
                || errors
                || connectedId.isBlank()
                || additionalAuth(response)) {
            log.warn(
                    "CODEF certificate registration response. organization={} resultCode={}"
                        + " accountCode={} derBytes={} keyBytes={} derAllZero={} keyAllZero={}",
                    organization,
                    safeCode(response.at("/result/code")),
                    safeCode(response.at("/data/errorList/0/code")),
                    derBytes,
                    keyBytes,
                    derAllZero,
                    keyAllZero);
        }
        if ("CF-00000".equals(response.at("/result/code").asText())
                && registered
                && !errors
                && !additionalAuth(response)
                && !connectedId.isBlank()) return CertificateProviderResult.connected(connectedId);
        String outerCode = response.at("/result/code").asText("");
        // Any success/partial/in-progress evidence wins over a detailed error. Never repeat create.
        if ((knownId == null && !connectedId.isBlank())
                || success.size() > 0
                || Set.of("CF-00016", "CF-04012", "CF-04034", "CF-04035").contains(outerCode))
            throw new CertificateProviderUncertainException();
        if (additionalAuth(response))
            return CertificateProviderResult.rejected("ADDITIONAL_AUTH_REQUIRED");
        String code = rejectionCode(response, organization);
        if (code == null || Set.of("CF-00016", "CF-04012", "CF-04034", "CF-04035").contains(code))
            throw new CertificateProviderUncertainException();
        return CertificateProviderResult.rejected(
                detailedErrorsEnabled ? code : "REGISTRATION_REJECTED");
    }

    private String rejectionCode(JsonNode response, String organization) {
        JsonNode errors = response.at("/data/errorList");
        for (JsonNode item : errors) {
            String itemOrganization = item.path("organization").asText("");
            if (!organization.equals(itemOrganization)
                    && !(itemOrganization.isEmpty() && errors.size() == 1)) continue;
            String code = safeCode(item.path("code"));
            if (!Set.of("UNAVAILABLE", "CF-00000").contains(code)) return code;
        }
        String code = safeCode(response.at("/result/code"));
        return Set.of("UNAVAILABLE", "CF-00000").contains(code) ? null : code;
    }

    private boolean allZero(byte[] value) {
        for (byte b : value) if (b != 0) return false;
        return true;
    }

    private void logFailure(String organization, RuntimeException failure) {
        int httpStatus = 0;
        String statusCode = "UNAVAILABLE", providerCode = "UNAVAILABLE";
        Throwable root = failure;
        for (int depth = 0; root != null && depth < 8; depth++) {
            if (root instanceof ApplicationException application) {
                String code = application.getStatusCode().getCode();
                if (code != null && code.matches("[A-Z][A-Z0-9_]{0,79}")) statusCode = code;
            }
            if (root instanceof CodefClient.CodefHttpException http) {
                httpStatus = http.status();
                providerCode = responseCode(http.responseBody());
            } else if (root instanceof RestClientResponseException http) {
                httpStatus = http.getStatusCode().value();
            }
            if (root.getCause() == null || root.getCause() == root) break;
            root = root.getCause();
        }
        log.warn(
                "CODEF certificate registration uncertain. organization={} httpStatus={}"
                    + " statusCode={} providerCode={} failureType={} causeType={}",
                organization,
                httpStatus,
                statusCode,
                providerCode,
                failure.getClass().getSimpleName(),
                root == null ? "UNAVAILABLE" : root.getClass().getSimpleName());
    }

    private String responseCode(String body) {
        try {
            return safeCode(client.decode(body).at("/result/code"));
        } catch (RuntimeException invalidResponse) {
            return "UNAVAILABLE";
        }
    }

    private static String safeCode(JsonNode node) {
        String code = node.asText("");
        return code.matches("CF-[0-9]{5}") ? code : "UNAVAILABLE";
    }

    public boolean delete(String organization, String connectedId) {
        JsonNode response;
        try {
            response =
                    client.postOnce(
                            "/v1/account/delete",
                            Map.of(
                                    "connectedId",
                                    connectedId,
                                    "accountList",
                                    List.of(
                                            Map.of(
                                                    "countryCode",
                                                    "KR",
                                                    "businessType",
                                                    "BK",
                                                    "clientType",
                                                    "P",
                                                    "organization",
                                                    organization,
                                                    "loginType",
                                                    "0"))));
        } catch (RuntimeException uncertain) {
            return false;
        }
        return "CF-00000".equals(response.at("/result/code").asText())
                && response.at("/data/errorList").size() == 0;
    }

    private boolean additionalAuth(JsonNode n) {
        if (n.path("continue2Way").asBoolean(false)) return true;
        for (JsonNode child : n) if (additionalAuth(child)) return true;
        return false;
    }

    public static final class CertificateProviderUncertainException extends RuntimeException {
        public CertificateProviderUncertainException() {
            super("Certificate provider outcome requires reconciliation");
        }
    }
}
