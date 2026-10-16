package com.amateur.howmuchbackend.infra.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("howmuch.certificate-reference")
public record CertificateReferenceProperties(
        boolean enabled,
        String commandTopic,
        String commandDltTopic,
        String commandGroup,
        int workerConcurrency) {
    public CertificateReferenceProperties {
        workerConcurrency = workerConcurrency == 0 ? 4 : workerConcurrency;
        if (workerConcurrency < 1 || workerConcurrency > 16)
            throw new IllegalArgumentException(
                    "Reference worker concurrency must be between 1 and 16");
        commandTopic = name(commandTopic, "finance.certificate-reference.command.v1");
        commandDltTopic = name(commandDltTopic, "finance.certificate-reference.command.v1.dlt");
        commandGroup = name(commandGroup, "howmuch-certificate-reference-v1");
        if (commandTopic.equals(commandDltTopic))
            throw new IllegalArgumentException("Reference topics must differ");
    }

    private static String name(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value;
        if (!result.matches("[a-zA-Z0-9._-]{1,249}"))
            throw new IllegalArgumentException("Invalid reference Kafka name");
        return result;
    }
}
