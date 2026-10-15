package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("howmuch.settlement.bank-lookup-budget")
public record SettlementBankLookupBudgetProperties(String namespace) {
    public SettlementBankLookupBudgetProperties {
        namespace = namespace == null ? "default" : namespace;
        if (!namespace.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Bank lookup budget namespace must contain 1..64 letters, digits, underscores or hyphens");
        }
    }
}
