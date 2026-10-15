package com.amateur.howmuchbackend.infra.certificate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

@ConfigurationProperties("howmuch.certificate")
public record CertificateProperties(
        boolean enabled,
        String kmsKeyId,
        String localWrappingKey,
        String confirmationKey,
        List<Institution> institutions,
        String commandTopic,
        String resultTopic,
        String commandDltTopic,
        String resultDltTopic,
        String commandGroup,
        String resultGroup,
        boolean detailedErrorsEnabled) {
    public CertificateProperties(boolean enabled, String kmsKeyId, String localWrappingKey,
            String confirmationKey, List<Institution> institutions, String commandTopic,
            String resultTopic, String commandDltTopic, String resultDltTopic,
            String commandGroup, String resultGroup) {
        this(enabled, kmsKeyId, localWrappingKey, confirmationKey, institutions, commandTopic,
                resultTopic, commandDltTopic, resultDltTopic, commandGroup, resultGroup, false);
    }

    @ConstructorBinding
    public CertificateProperties {
        kmsKeyId = kmsKeyId == null ? "" : kmsKeyId;
        localWrappingKey = localWrappingKey == null ? "" : localWrappingKey;
        confirmationKey = confirmationKey == null ? "" : confirmationKey;
        institutions = institutions == null ? List.of() : List.copyOf(institutions);
        var institutionCodes = new java.util.HashSet<String>();
        for (var institution : institutions) {
            if (institution.code() == null
                    || !institution.code().matches("[0-9]{4}")
                    || !institutionCodes.add(institution.code())) {
                throw new IllegalArgumentException(
                        "Certificate institution codes must be unique four-digit codes");
            }
        }
        commandTopic = defaultValue(commandTopic, "certificate.registration.command.v1");
        resultTopic = defaultValue(resultTopic, "certificate.registration.result.v1");
        commandDltTopic = defaultValue(commandDltTopic, "certificate.registration.command.v1.dlt");
        resultDltTopic = defaultValue(resultDltTopic, "certificate.registration.result.v1.dlt");
        commandGroup = defaultValue(commandGroup, "howmuch-certificate-provider-v1");
        resultGroup = defaultValue(resultGroup, "howmuch-certificate-registration-v1");
        if (new java.util.HashSet<>(
                                List.of(commandTopic, resultTopic, commandDltTopic, resultDltTopic))
                        .size()
                != 4) throw new IllegalArgumentException("Certificate topics must differ");
    }

    private static String defaultValue(String v, String fallback) {
        String s = v == null || v.isBlank() ? fallback : v;
        if (!s.matches("[a-zA-Z0-9._-]{1,249}"))
            throw new IllegalArgumentException("Invalid certificate Kafka name");
        return s;
    }

    public record Institution(
            String code,
            // Legacy configuration compatibility; does not gate certificate support.
            boolean reviewed,
            boolean birthDateRequired,
            String reference) {}

    @Override
    public String toString() {
        return "CertificateProperties[enabled=" + enabled + "]";
    }
}
